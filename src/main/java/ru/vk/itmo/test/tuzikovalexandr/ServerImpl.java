package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.tuzikovalexandr.dao.Dao;
import ru.vk.itmo.test.tuzikovalexandr.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerImpl extends HttpServer {

    private final ExecutorService executorService;
    private final ExecutorService futureExecutorService;
    private final HttpClient httpClient;
    private final ConsistentHashing consistentHashing;
    private final RequestHandler requestHandler;
    private final ReadRepairManager readRepairManager;
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);

    private final String selfUrl;
    private final int clusterSize;

    public ServerImpl(ServiceConfig config, Dao dao, Worker worker,
                      ConsistentHashing consistentHashing) throws IOException {
        super(createServerConfig(config));
        this.executorService = worker.getExecutorService();
        this.futureExecutorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(), new CustomThreadFactory("future-executor"));
        this.consistentHashing = consistentHashing;
        this.selfUrl = config.selfUrl();
        this.clusterSize = config.clusterUrls().size();
        this.requestHandler = new RequestHandler(dao);
        this.readRepairManager = new ReadRepairManager();
        this.httpClient = HttpClient.newBuilder()
                .executor(worker.getExecutorService()).build();
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (Constants.METHODS.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            if (request.getURI().startsWith(Constants.RANGE_REQUEST)) {
                String paramStart = request.getParameter("start=");
                String paramEnd = request.getParameter("end=");

                rangeRequest(session, paramStart, paramEnd);
                return;
            }

            if (!request.getURI().startsWith(Constants.ID_REQUEST)
                    || !Constants.METHODS.contains(request.getMethod())) {
                handleDefault(request, session);
                return;
            }

            String paramId = request.getParameter("id=");
            String fromStr = request.getParameter("from=");
            String ackStr = request.getParameter("ack=");

            int from = fromStr == null || fromStr.isEmpty() ? clusterSize : Integer.parseInt(fromStr);
            int ack = ackStr == null || ackStr.isEmpty() ? from / 2 + 1 : Integer.parseInt(ackStr);

            if (ack == 0 || from > clusterSize || ack > from || paramId == null || paramId.isEmpty()) {
                sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }

            long processingStartTime = System.currentTimeMillis();
            executorService.execute(() -> {
                try {
                    processingRequest(request, session, processingStartTime, paramId, from, ack);
                } catch (IOException e) {
                    log.error("Exception while sending close connection", e);
                    session.scheduleClose();
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendResponse(new Response(Constants.TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private void rangeRequest(HttpSession session, String start, String end) {
        if (start == null || start.isBlank()) {
            sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        executorService.execute(() -> {
            try {
                Iterator<EntryWithTimestamp<MemorySegment>> entries = requestHandler.getEntries(start, end);

                final StreamResponse streamResponse = new StreamResponse(Response.OK, entries);
                streamResponse.stream(session);
            } catch (IOException e) {
                log.error("Exception while sending close connection", e);
                session.scheduleClose();
            }
        });
    }

    private void processingRequest(Request request, HttpSession session, long processingStartTime,
                                   String paramId, int from, int ack) throws IOException {
        if (System.currentTimeMillis() - processingStartTime > Constants.REQUEST_TIMEOUT) {
            session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        try {
            if (request.getHeader(Constants.HTTP_TERMINATION_HEADER) == null) {
                sendResponse(session, handleProxyRequest(request, session, paramId, from, ack));
            } else {
                sendResponse(session, requestHandler.handle(request, paramId));
            }
        } catch (Exception e) {
            if (e.getClass() == HttpException.class) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            } else {
                log.error("Exception during handleRequest: ", e);
                session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            }
        }
    }

    private void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            log.error("Error sending response", e);
            session.scheduleClose();
        }
    }

    private HttpRequest createProxyRequest(Request request, String nodeUrl, String params) {
        return HttpRequest.newBuilder(URI.create(nodeUrl + "/v0/entity?id=" + params))
                .method(request.getMethodName(), request.getBody() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .setHeader(Constants.HTTP_TERMINATION_HEADER, "true")
                .build();
    }

    private CompletableFuture<Response> sendProxyRequest(HttpRequest httpRequest) {
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(this::processingResponse, futureExecutorService);
    }

    private Response processingResponse(HttpResponse<byte[]> response) {
        String statusCode = Constants.HTTP_CODE.getOrDefault(response.statusCode(), null);
        if (statusCode == null) {
            return new Response(Response.INTERNAL_ERROR, response.body());
        } else {
            Response newResponse = new Response(statusCode, response.body());
            long timestamp = response.headers()
                    .firstValueAsLong(Constants.HTTP_TIMESTAMP_HEADER).orElse(0);
            newResponse.addHeader(Constants.NIO_TIMESTAMP_HEADER + timestamp);
            return newResponse;
        }
    }

    private Map<String, CompletableFuture<Response>> sendProxyRequests(Map<String, HttpRequest> httpRequests,
                                                                List<String> nodeUrls) {
        Map<String, CompletableFuture<Response>> responses = new HashMap<>();
        for (String nodeUrl : nodeUrls) {
            HttpRequest httpRequest = httpRequests.get(nodeUrl);
            if (!Objects.equals(selfUrl, nodeUrl)) {
                responses.put(nodeUrl, sendProxyRequest(httpRequest));
            }
        }
        return responses;
    }

    private Response handleProxyRequest(Request request, HttpSession session,
                                                           String paramId, int from, int ack) {
        List<String> nodeUrls = consistentHashing.getNodes(paramId, from);

        if (nodeUrls.size() < from) {
            sendResponse(session, new Response(Constants.NOT_ENOUGH_REPLICAS, Response.EMPTY));
        }

        HashMap<String, HttpRequest> httpRequests = new HashMap<>(nodeUrls.size());
        for (String nodeUrl : nodeUrls) {
            httpRequests.put(nodeUrl, createProxyRequest(request, nodeUrl, paramId));
        }

        Map<String, CompletableFuture<Response>> responses = sendProxyRequests(httpRequests, nodeUrls);

        if (httpRequests.get(selfUrl) != null) {
            responses.put(
                    selfUrl,
                    CompletableFuture.supplyAsync(() -> requestHandler.handle(request, paramId))
            );
        }

        return getQuorumResult(request, from, ack, responses, paramId);
    }

    private Response getQuorumResult(Request request, int from, int ack,
                                     Map<String, CompletableFuture<Response>> responses, String paramId) {
        List<ResponseWithUrl> successResponses = new CopyOnWriteArrayList<>();
        CompletableFuture<Response> result = new CompletableFuture<>();
        AtomicInteger successResponseCount = new AtomicInteger();
        AtomicInteger errorResponseCount = new AtomicInteger();

        for (Map.Entry<String, CompletableFuture<Response>> responseFuture : responses.entrySet()) {
            responseFuture.getValue().whenCompleteAsync((response, throwable) -> {
                if (throwable == null || (response != null && response.getStatus() < Constants.SERVER_ERROR)) {
                    successResponseCount.incrementAndGet();
                    successResponses.add(new ResponseWithUrl(responseFuture.getKey(), response));
                } else {
                    errorResponseCount.incrementAndGet();
                }

                if (successResponseCount.get() >= ack) {
                    result.complete(getResult(request, successResponses, paramId));
                }

                if (errorResponseCount.get() == from - ack + 1) {
                    result.complete(new Response(Constants.NOT_ENOUGH_REPLICAS, Response.EMPTY));
                }
            }, futureExecutorService).exceptionally(e -> new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }

        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (ExecutionException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response getResult(Request request, List<ResponseWithUrl> successResponses, String paramId) {
        List<ResponseWithUrl> sortedResponses = new ArrayList<>(successResponses);

        if (request.getMethod() == Request.METHOD_GET) {
            sortResponses(sortedResponses);

            if (readRepairManager.checkReadRepair(sortedResponses)) {
                List<String> nodesToUpdate = readRepairManager.getNodesForUpdate(successResponses,
                        sortedResponses.getLast().getResponse());

                readRepairManager.updateValues(selfUrl, requestHandler, nodesToUpdate,
                        sortedResponses.getLast().getResponse(), paramId, httpClient);
            }

            return sortedResponses.getLast().getResponse();
        } else {
            return sortedResponses.getFirst().getResponse();
        }
    }

    private void sortResponses(List<ResponseWithUrl> successResponses) {
        successResponses.sort(Comparator.comparingLong(r -> {
            String timestamp = r.getResponse().getHeader(Constants.NIO_TIMESTAMP_HEADER);
            return timestamp == null ? 0 : Long.parseLong(timestamp);
        }));
    }
}
