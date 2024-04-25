package ru.vk.itmo.test.pavelemelyanov;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.pavelemelyanov.dao.Dao;
import ru.vk.itmo.test.pavelemelyanov.dao.EntryWithTimestamp;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class MyServer extends HttpServer {
    private static final String RANGE_REQUEST = "/v0/entities?start=";
    private static final String ID_REQUEST = "/v0/entity?id=";
    private static final String ID_PARAM = "id=";
    private static final String FROM_PARAM = "from=";
    private static final String ACK_PARAM = "ack=";
    private static final int SERVER_ERROR = 500;
    private static final Logger LOG = LoggerFactory.getLogger(MyServer.class);

    private final ExecutorService workersPool;
    private final HttpClient httpClient;
    private final ConsistentHashing shards;
    private final RequestHandler requestHandler;
    private final String selfUrl;
    private final int clusterSize;

    public MyServer(ServiceConfig config, Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> dao,
            ExecutorServiceWrapper worker, ConsistentHashing shards) throws IOException {
        super(ServerConfiguration.createServerConfig(config));
        this.selfUrl = config.selfUrl();
        this.shards = shards;
        this.requestHandler = new RequestHandler(dao);
        this.workersPool = worker.getExecutorService();
        this.clusterSize = config.clusterUrls().size();
        this.httpClient = HttpClient.newBuilder().executor(workersPool).build();
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = HttpUtils.SUPPORTED_METHODS.contains(request.getMethod())
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            if (request.getURI().startsWith(RANGE_REQUEST)) {
                String paramStart = request.getParameter("start=");
                String paramEnd = request.getParameter("end=");
                rangeRequest(session, paramStart, paramEnd);
                return;
            }
            if (!request.getURI().startsWith(ID_REQUEST)
                    || !HttpUtils.SUPPORTED_METHODS.contains(request.getMethod())) {
                handleDefault(request, session);
                return;
            }
            String paramId = request.getParameter(ID_PARAM);
            String fromStr = request.getParameter(FROM_PARAM);
            String ackStr = request.getParameter(ACK_PARAM);
            int from = fromStr == null || fromStr.isBlank() ? clusterSize : Integer.parseInt(fromStr);
            int ack = ackStr == null || ackStr.isBlank() ? from / 2 + 1 : Integer.parseInt(ackStr);
            if (ack == 0 || from > clusterSize || ack > from || paramId == null || paramId.isEmpty()) {
                sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            long processingStartTime = System.currentTimeMillis();
            workersPool.execute(() -> {
                try {
                    processingRequest(request, session, processingStartTime, paramId, from, ack);
                } catch (IOException e) {
                    LOG.error("Exception while sending close connection", e);
                    session.scheduleClose();
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendResponse(new Response("429 Too Many Requests", Response.EMPTY));
        }
    }

    private void processingRequest(Request request, HttpSession session, long processingStartTime,
                                   String paramId, int from, int ack) throws IOException {
        if (System.currentTimeMillis() - processingStartTime > HttpUtils.REQUEST_TIMEOUT_IN_MILLIS) {
            session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }
        try {
            if (request.getHeader(HttpUtils.HTTP_TERMINATION_HEADER) == null) {
                sendResponse(session, handleProxyRequest(request, paramId, from, ack));
                return;
            }
            sendResponse(session, requestHandler.handle(request, paramId));
        } catch (Exception e) {
            LOG.error("Exception during handleRequest: ", e);
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    private void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            LOG.error("Error sending response", e);
            session.scheduleClose();
        }
    }

    private HttpRequest createProxyRequest(Request request, String nodeUrl, String params) {
        var bodyPublisher = request.getBody() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.getBody());
        return HttpRequest.newBuilder(URI.create(nodeUrl + ID_REQUEST + params))
                .method(request.getMethodName(), bodyPublisher)
                .setHeader(HttpUtils.HTTP_TERMINATION_HEADER, "true")
                .build();
    }

    private CompletableFuture<Response> sendProxyRequest(HttpRequest httpRequest) {
        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(this::processingResponse);
    }

    private Response processingResponse(HttpResponse<byte[]> response) {
        String statusCode = HttpUtils.HTTP_CODE.getOrDefault(response.statusCode(), null);
        if (statusCode == null) {
            return new Response(Response.INTERNAL_ERROR, response.body());
        }
        var newResponse = new Response(statusCode, response.body());
        long timestamp = response.headers().firstValueAsLong(HttpUtils.HTTP_TIMESTAMP_HEADER).orElse(0);
        newResponse.addHeader(HttpUtils.NIO_TIMESTAMP_HEADER + timestamp);
        return newResponse;
    }

    private List<CompletableFuture<Response>> sendProxyRequests(Map<String, HttpRequest> httpRequests,
                                                                List<String> nodeUrls) {
        List<CompletableFuture<Response>> responses = new ArrayList<>();
        for (String nodeUrl : nodeUrls) {
            HttpRequest httpRequest = httpRequests.get(nodeUrl);
            if (!Objects.equals(selfUrl, nodeUrl)) {
                responses.add(sendProxyRequest(httpRequest));
            }
        }
        return responses;
    }

    private Response handleProxyRequest(Request request, String paramId, int from, int ack) {
        List<String> nodeUrls = shards.getNodes(paramId, from);
        if (nodeUrls.size() < from) {
            return new Response(HttpUtils.NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
        HashMap<String, HttpRequest> httpRequests = new HashMap<>(nodeUrls.size());
        for (var nodeUrl : nodeUrls) {
            httpRequests.put(nodeUrl, createProxyRequest(request, nodeUrl, paramId));
        }
        List<CompletableFuture<Response>> responses = sendProxyRequests(httpRequests, nodeUrls);
        if (httpRequests.containsKey(selfUrl)) {
            responses.add(CompletableFuture.supplyAsync(() -> requestHandler.handle(request, paramId)));
        }
        return getQuorumResult(request, from, ack, responses);
    }

    private Response getQuorumResult(Request request, int from, int ack,
                                     List<CompletableFuture<Response>> responses) {
        List<Response> successResponses = new CopyOnWriteArrayList<>();
        CompletableFuture<Response> result = new CompletableFuture<>();
        AtomicInteger successResponseCount = new AtomicInteger();
        AtomicInteger errorResponseCount = new AtomicInteger();
        for (var responseFuture : responses) {
            responseFuture.whenCompleteAsync((response, throwable) -> {
                if (throwable == null || (response != null && response.getStatus() < SERVER_ERROR)) {
                    successResponseCount.incrementAndGet();
                    successResponses.add(response);
                } else {
                    errorResponseCount.incrementAndGet();
                }
                if (successResponseCount.get() >= ack) {
                    result.complete(getResult(request, successResponses));
                }
                if (errorResponseCount.get() == from - ack + 1) {
                    result.complete(new Response(HttpUtils.NOT_ENOUGH_REPLICAS, Response.EMPTY));
                }
            }).exceptionally(e -> new Response(Response.INTERNAL_ERROR, Response.EMPTY));
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

    private Response getResult(Request request, List<Response> successResponses) {
        if (request.getMethod() == Request.METHOD_GET) {
            sortResponses(successResponses);
            return successResponses.getLast();
        }
        return successResponses.getFirst();
    }

    private void sortResponses(List<Response> successResponses) {
        successResponses.sort(Comparator.comparingLong(r -> {
            String timestamp = r.getHeader(HttpUtils.NIO_TIMESTAMP_HEADER);
            return timestamp == null ? 0 : Long.parseLong(timestamp);
        }));
    }

    private void rangeRequest(HttpSession session, String startParam, String endParam) {
        if (startParam == null || startParam.isBlank()) {
            sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }
        workersPool.execute(() -> {
            try {
                Iterator<EntryWithTimestamp<MemorySegment>> entries = requestHandler.getEntries(startParam, endParam);
                var streamResponse = new StreamResponse(Response.OK, entries);
                streamResponse.stream(session);
            } catch (IOException e) {
                LOG.error("Exception while closing connection", e);
                session.scheduleClose();
            }
        });
    }
}
