package ru.vk.itmo.test.alenkovayulya;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.alenkovayulya.dao.BaseEntryWithTimestamp;
import ru.vk.itmo.test.alenkovayulya.dao.Dao;
import ru.vk.itmo.test.alenkovayulya.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.vk.itmo.test.alenkovayulya.ShardRouter.REDIRECT_HEADER;
import static ru.vk.itmo.test.alenkovayulya.ShardRouter.TIMESTAMP_HEADER;
import static ru.vk.itmo.test.alenkovayulya.ShardRouter.redirectRequest;

public class ServerImpl extends HttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerImpl.class);
    private final Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> referenceDao;
    private final ExecutorService executorService;
    private final String url;
    private final ShardSelector shardSelector;
    private static final Set<Integer> ALLOWED_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );
    private static final int[] AVAILABLE_GOOD_RESPONSE_CODES = new int[] {200, 201, 202, 404};

    public ServerImpl(ServiceConfig serviceConfig,
                      Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> referenceDao,
                      ExecutorService executorService, ShardSelector shardSelector) throws IOException {
        super(createServerConfig(serviceConfig));
        this.referenceDao = referenceDao;
        this.executorService = executorService;
        this.url = serviceConfig.selfUrl();
        this.shardSelector = shardSelector;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (!ALLOWED_METHODS.contains(request.getMethod())) {
            sendEmptyResponse(Response.METHOD_NOT_ALLOWED, session);
            return;
        }

        String id = request.getParameter("id=");
        if (isEmptyId(id)) {
            sendEmptyResponse(Response.BAD_REQUEST, session);
            return;
        }

        if (request.getHeader(REDIRECT_HEADER) != null) {
            long timestamp = resolveTimestamp(request.getHeader(TIMESTAMP_HEADER));
            Response response = handleInternalRequest(request, id, timestamp);
            session.sendResponse(response);
        } else {
            handleAsLeader(request, session, id);
        }
    }

    private void handleAsLeader(Request request, HttpSession session, String id) {
        String ackS = request.getParameter("ack=");
        String fromS = request.getParameter("from=");

        int from = isEmptyId(fromS)
                ? shardSelector.getClusterSize() : Integer.parseInt(fromS);
        int ack = isEmptyId(ackS)
                ? quorum(from) : Integer.parseInt(ackS);

        if (ack == 0 || ack > from) {
            sendEmptyResponse(Response.BAD_REQUEST, session);
        }

        try {
            executorService.execute(() -> {
                try {
                    collectResponses(request, session, id, from, ack);
                } catch (Exception e) {
                    try {
                        session.sendError(Response.BAD_REQUEST, e.getMessage());
                    } catch (IOException ex) {
                        LOGGER.info("Exception during sending the response: ", ex);
                        session.close();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.error("Request rejected by policy", e);
            sendEmptyResponse(Response.SERVICE_UNAVAILABLE, session);
        }
    }

    private void collectResponses(Request request,
            HttpSession session,
            String id,
            int from,
            int ack
    ) {
        List<CompletableFuture<Response>> asyncResponses = new CopyOnWriteArrayList<>();
        long timestamp = System.currentTimeMillis();
        int firstOwnerShardIndex = shardSelector.getOwnerShardIndex(id);

        for (int i = 0; i < from; i++) {
            CompletableFuture<Response> asyncResponse;
            int shardIndex = (firstOwnerShardIndex + i) % shardSelector.getClusterSize();

            if (isRedirectNeeded(shardIndex)) {
                asyncResponse = handleRedirect(request, timestamp, shardIndex);
            } else {
                asyncResponse = handleInternalRequestAsync(request, id, timestamp);
            }

            asyncResponses.add(asyncResponse);

        }

        handleAsyncResponses(session, ack, from, request, asyncResponses);

    }

    private void handleAsyncResponses(
            HttpSession session, int ack, int from, Request request,
            List<CompletableFuture<Response>> completableFutureResponses
    ) {
        List<Response> validResponses = new CopyOnWriteArrayList<>();
        AtomicBoolean isEnoughValidResponses = new AtomicBoolean();
        AtomicInteger allResponsesCounter = new AtomicInteger();

        for (CompletableFuture<Response> completableFuture : completableFutureResponses) {
            completableFuture.whenCompleteAsync((response, throwable) -> {
                if (isEnoughValidResponses.get()) {
                    return;
                }
                allResponsesCounter.incrementAndGet();

                if (throwable != null) {
                    response = new Response(Response.INTERNAL_ERROR);
                }

                if (isValidResponse(response)) {
                    validResponses.add(response);
                }

                sendResponseIfEnoughReplicasResponsesNumber(request,
                        isEnoughValidResponses,
                        session,
                        validResponses,
                        ack);

                if (allResponsesCounter.get() == from && validResponses.size() < ack) {
                    sendEmptyResponse("504 Not Enough Replicas", session);
                }
            }, executorService).exceptionally((th) -> new Response(Response.INTERNAL_ERROR));
        }
    }

    private void sendResponseIfEnoughReplicasResponsesNumber(
            Request request,
            AtomicBoolean isEnoughValidResponses,
            HttpSession session,
            List<Response> responses,
            int ack
    ) {
        try {
            if (responses.size() >= ack) {
                isEnoughValidResponses.set(true);
                if (request.getMethod() == Request.METHOD_GET) {
                    session.sendResponse(getResponseWithMaxTimestamp(responses));
                } else {
                    session.sendResponse(responses.getFirst());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Exception during send win response: ", e);
            sendEmptyResponse(Response.INTERNAL_ERROR, session);
            session.close();
        }
    }

    private boolean isValidResponse(Response response) {
        return Arrays.stream(AVAILABLE_GOOD_RESPONSE_CODES)
                .anyMatch(code -> code == response.getStatus());
    }

    private CompletableFuture<Response> handleRedirect(Request request, long timestamp, int nodeIndex) {
        return redirectRequest(request.getMethodName(),
                request.getParameter("id="),
                shardSelector.getShardUrlByIndex(nodeIndex),
                request.getBody() == null
                        ? new byte[0] : request.getBody(), timestamp);
    }

    private CompletableFuture<Response> handleInternalRequestAsync(Request request, String id, long timestamp) {
        return CompletableFuture.supplyAsync(() ->
                handleInternalRequest(request, id, timestamp), ShardRouter.proxyExecutor);
    }

    private Response handleInternalRequest(Request request, String id, long timestamp) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                EntryWithTimestamp<MemorySegment> entry = referenceDao.get(
                        convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)));

                if (entry == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                } else if (entry.value() == null) {
                    Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                    response.addHeader(TIMESTAMP_HEADER + ": " + entry.timestamp());
                    return response;
                } else {
                    Response response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
                    response.addHeader(TIMESTAMP_HEADER + ": " + entry.timestamp());
                    return response;
                }
            }
            case Request.METHOD_PUT -> {
                referenceDao.upsert(new BaseEntryWithTimestamp<>(
                        convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                        convertBytesToMemorySegment(request.getBody()), timestamp));

                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                referenceDao.upsert(new BaseEntryWithTimestamp<>(
                        convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                        null, timestamp));

                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private boolean isRedirectNeeded(int shardIndex) {
        return !url.equals(shardSelector.getShardUrlByIndex(shardIndex));
    }

    private boolean isEmptyId(String id) {
        return id == null || (id.isEmpty() && id.isBlank());
    }

    private MemorySegment convertBytesToMemorySegment(byte[] byteArray) {
        return MemorySegment.ofArray(byteArray);
    }

    private int quorum(int from) {
        return from / 2 + 1;
    }

    private long resolveTimestamp(String timestampHeader) {
        if (isEmptyId(timestampHeader)) {
            return 0L;
        }
        try {
            return Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void sendEmptyResponse(String response, HttpSession session) {
        var emptyRes = new Response(response, Response.EMPTY);
        try {
            session.sendResponse(emptyRes);
        } catch (IOException e) {
            LOGGER.info("Exception during sending the empty response: ", e);
        }
    }

    private Response getResponseWithMaxTimestamp(List<Response> responses) {
        Response result = responses.getFirst();
        long max = 0;
        for (Response response : responses) {
            String timestampHeader = response.getHeaders()[response.getHeaderCount() - 1];

            long timestamp = resolveTimestamp(timestampHeader);
            if (max < timestamp) {
                max = timestamp;
                result = response;
            }
        }

        return result;
    }
}
