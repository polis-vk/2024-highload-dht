package ru.vk.itmo.test.tuzikovalexandr;

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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerImpl extends HttpServer {

    private final ExecutorService executorService;
    private final HttpClient httpClient;
    private final ConsistentHashing consistentHashing;
    private final List<String> clusterUrls;
    private final RequestHandler requestHandler;
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);

    private final String selfUrl;
    private final int clusterSize;

    public ServerImpl(ServiceConfig config, Dao dao, Worker worker,
                      ConsistentHashing consistentHashing) throws IOException {
        super(createServerConfig(config));
        this.executorService = worker.getExecutorService();
        this.consistentHashing = consistentHashing;
        this.selfUrl = config.selfUrl();
        this.clusterSize = config.clusterUrls().size();
        this.clusterUrls = config.clusterUrls();
        this.requestHandler = new RequestHandler(dao);
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(2)).build();
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
            if (!request.getURI().startsWith("/v0/entity?id=") || !Constants.METHODS.contains(request.getMethod())) {
                handleDefault(request, session);
                return;
            }

            String paramId = request.getParameter("id=");

            if (paramId == null || paramId.isEmpty()) {
                sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }

            String fromStr = request.getParameter("from=");
            String ackStr = request.getParameter("ack=");

            int from = fromStr == null || fromStr.isEmpty() ? clusterSize : Integer.parseInt(fromStr);
            int ack = ackStr == null || ackStr.isEmpty() ? from / 2 + 1 : Integer.parseInt(ackStr);

            if (ack == 0 || from > clusterSize || ack > from) {
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

    private void processingRequest(Request request, HttpSession session, long processingStartTime,
                                   String paramId, int from, int ack) throws IOException {
        if (System.currentTimeMillis() - processingStartTime > Constants.REQUEST_TIMEOUT) {
            session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        try {
            if (request.getHeader(Constants.HTTP_TERMINATION_HEADER) == null) {
                CompletableFuture<Response> handleProxyResponse =
                        handleProxyRequest(request, session, paramId, from, ack);
                handleProxyResponse = handleProxyResponse.whenComplete((response, throwable) ->
                        sendResponse(session, response));
                checkCompletableFuture(handleProxyResponse);
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
        final CompletableFuture<Response> httpResponse = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> byteResponse =
        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        httpResponse.completeExceptionally(throwable);
                        return;
                    }
                    httpResponse.complete(processingResponse(response));
                });

        checkCompletableFuture(byteResponse);
        return httpResponse;
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

    private CompletableFuture<Response> handleProxyRequest(Request request, HttpSession session,
                                                           String paramId, int from, int ack) {
        List<String> nodeUrls = consistentHashing.getNodes(paramId, clusterUrls, from);

        if (nodeUrls.size() < from) {
            sendResponse(session, new Response(Constants.NOT_ENOUGH_REPLICAS, Response.EMPTY));
        }

        HashMap<String, HttpRequest> httpRequests = new HashMap<>(nodeUrls.size());
        for (String nodeUrl : nodeUrls) {
            httpRequests.put(nodeUrl, createProxyRequest(request, nodeUrl, paramId));
        }

        List<CompletableFuture<Response>> responses = sendProxyRequests(httpRequests, nodeUrls);

        if (httpRequests.get(selfUrl) != null) {
            final CompletableFuture<Response> httpResponse = new CompletableFuture<>();
            executorService.execute(() -> {
                try {
                    httpResponse.complete(requestHandler.handle(request, paramId));
                } catch (Exception e) {
                    httpResponse.completeExceptionally(e);
                }
            });
            responses.add(httpResponse);
        }

        return getQuorumResult(request, from, ack, responses);
    }

    private CompletableFuture<Response> getQuorumResult(Request request, int from, int ack,
                                                        List<CompletableFuture<Response>> responses) {
        List<Response> successResponses = new ArrayList<>();
        CompletableFuture<Response> result = new CompletableFuture<>();
        AtomicInteger successResponseCount = new AtomicInteger(0);
        AtomicInteger errorResponseCount = new AtomicInteger(0);

        for (CompletableFuture<Response> responseFuture : responses) {
            responseFuture = responseFuture.whenComplete((response, throwable) -> {
                if (throwable == null || (response != null && response.getStatus() < Constants.SERVER_ERROR)) {
                    successResponseCount.incrementAndGet();
                    successResponses.add(response);
                } else {
                    errorResponseCount.incrementAndGet();
                }

                if (successResponseCount.get() == ack) {
                    if (request.getMethod() == Request.METHOD_GET) {
                        successResponses.sort(Comparator.comparingLong(r -> {
                            String timestamp = r.getHeader(Constants.NIO_TIMESTAMP_HEADER);
                            return timestamp == null ? 0 : Long.parseLong(timestamp);
                        }));
                        result.complete(successResponses.getLast());
                    } else {
                        result.complete(successResponses.getFirst());
                    }
                } else if (errorResponseCount.get() == from - ack + 1) {
                    result.complete(new Response(Constants.NOT_ENOUGH_REPLICAS, Response.EMPTY));
                }
            });

            checkCompletableFuture(responseFuture);
        }

        return result;
    }

    private void checkCompletableFuture(CompletableFuture<?> completableFuture) {
        if (completableFuture == null) {
            log.error("Error CompletableFuture");
        }
    }
}
