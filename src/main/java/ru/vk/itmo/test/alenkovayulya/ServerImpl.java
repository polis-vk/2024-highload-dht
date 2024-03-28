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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static ru.vk.itmo.test.alenkovayulya.ShardRouter.REDIRECT_HEADER;
import static ru.vk.itmo.test.alenkovayulya.ShardRouter.TIMESTAMP_HEADER;
import static ru.vk.itmo.test.alenkovayulya.ShardRouter.redirectRequest;

public class ServerImpl extends HttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerImpl.class);
    private final Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> referenceDao;
    private final ExecutorService executorService;
    private final String url;
    private final ShardSelector shardSelector;
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
    ) throws IOException {
        List<Response> responses = new ArrayList<>();
        long timestamp = System.currentTimeMillis();
        int firstOwnerShardIndex = shardSelector.getOwnerShardIndex(id);

        for (int i = 0; i < from; i++) {
            int shardIndex = (firstOwnerShardIndex + i) % shardSelector.getClusterSize();

            if (isRedirectNeeded(shardIndex)) {
                handleRedirect(request, timestamp, shardIndex, responses);
            } else {
                Response response = handleInternalRequest(request, id, timestamp);
                responses.add(response);
            }

        }

        checkReplicasResponsesNumber(request, session, responses, ack);
    }

    private void checkReplicasResponsesNumber(
            Request request,
            HttpSession session,
            List<Response> responses,
            int ack
    ) throws IOException {
        if (responses.size() >= ack) {
            if (request.getMethod() == Request.METHOD_GET) {
                session.sendResponse(getResponseWithMaxTimestamp(responses));
            } else {
                session.sendResponse(responses.getFirst());
            }
        } else {
            sendEmptyResponse("504 Not Enough Replicas", session);
        }
    }

    private void handleRedirect(Request request, long timestamp, int nodeIndex, List<Response> responses) {
        Response response = redirectRequest(request.getMethodName(),
                request.getParameter("id="),
                shardSelector.getShardUrlByIndex(nodeIndex),
                request.getBody() == null
                        ? new byte[0] : request.getBody(), timestamp);
        boolean correctRes = Arrays.stream(AVAILABLE_GOOD_RESPONSE_CODES)
                .anyMatch(code -> code == response.getStatus());
        if (correctRes) {
            responses.add(response);
        }
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
            session.close();
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
