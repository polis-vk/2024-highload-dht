package ru.vk.itmo.test.proninvalentin;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.proninvalentin.dao.ReferenceDao;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Server extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final ExecutorService workerPool;
    private final ShardingAlgorithm shardingAlgorithm;
    private final HttpClient httpClient;
    private final RequestHandler requestHandler;
    private final ScheduledExecutorService timeoutChecker = Executors.newSingleThreadScheduledExecutor();

    private final String selfUrl;
    private final List<String> clusterUrls;
    private final long requestMaxTimeToTakeInWorkInMillis;
    private final long httpRequestTimeoutInMillis;

    public Server(ServiceConfig config, ReferenceDao dao, WorkerPool workerPool,
                  ShardingAlgorithm shardingAlgorithm, ServerConfig serverConfig)
            throws IOException {
        super(Utils.createServerConfig(config));

        this.shardingAlgorithm = shardingAlgorithm;
        this.workerPool = workerPool.pool;
        this.httpClient = HttpClient.newBuilder()
                .executor(workerPool.pool)
                .build();
        this.selfUrl = config.selfUrl();
        this.clusterUrls = config.clusterUrls();
        this.requestMaxTimeToTakeInWorkInMillis = serverConfig.getRequestMaxTimeToTakeInWorkInMillis();
        this.httpRequestTimeoutInMillis = serverConfig.getHttpRequestTimeoutInMillis();
        this.requestHandler = new RequestHandler(dao);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        try {
            long createdAt = System.currentTimeMillis();
            workerPool.execute(() -> processRequest(request, session, createdAt));
        } catch (RejectedExecutionException e) {
            logger.error("New request processing task cannot be scheduled for execution", e);
            safetySendResponse(session, new Response(Constants.TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private static void safetySendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("Error while sending response", e);
        }
    }

    private void processRequest(Request request, HttpSession session, long createdAt) {
        boolean timeoutExpired = System.currentTimeMillis() - createdAt > requestMaxTimeToTakeInWorkInMillis;
        if (timeoutExpired) {
            safetySendResponse(session, new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        // To prevent sending unnecessary proxied requests to the nodes
        if (!Utils.hasHandler(request) || !Utils.isSupportedMethod(request.getMethod())) {
            handleDefault(request, session);
            return;
        }

        RequestParameters parameters = new RequestParameters(
                request.getParameter(Constants.ID_PARAMETER_NAME),
                request.getParameter(Constants.FROM_PARAMETER_NAME),
                request.getParameter(Constants.ACK_PARAMETER_NAME),
                clusterUrls.size()
        );

        if (!parameters.isValid()) {
            safetySendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        if (request.getHeader(Constants.TERMINATION_HEADER) == null) {
            CompletableFuture<Response> handleLeaderRequestFuture = handleLeaderRequest(request, parameters);
            handleLeaderRequestFuture.whenComplete((response, throwable) -> safetySendResponse(session, response));
//            checkForTimeout(handleLeaderRequestFuture);
        } else {
            safetySendResponse(session, safetyHandleRequest(request, parameters.key()));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        int httpMethod = request.getMethod();
        Response response = Utils.isSupportedMethod(httpMethod)
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        safetySendResponse(session, response);
    }

    private static Response processProxyResponse(HttpResponse<byte[]> httpResponse) {
        String statusCode = Utils.httpCodeMapping.getOrDefault(httpResponse.statusCode(), null);
        if (statusCode == null) {
            logger.error("The proxied node returned a response with an unexpected status: "
                    + httpResponse.statusCode());
            return new Response(Response.INTERNAL_ERROR, httpResponse.body());
        } else {
            Response response = new Response(statusCode, httpResponse.body());
            long timestamp = httpResponse.headers()
                    .firstValueAsLong(Constants.HTTP_TIMESTAMP_HEADER).orElse(0);
            response.addHeader(Constants.NIO_TIMESTAMP_HEADER + timestamp);
            return response;
        }
    }

    private Response safetyHandleRequest(Request request, String entryId) {
//        logger.debug("[%s] Process request: %s %s".formatted(selfUrl, request.getMethodName(),
//                request.getURI()));
        try {
            return requestHandler.handle(request, entryId);
        } catch (Exception e) {
            logger.error("Error while processing request", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private void checkForTimeout(CompletableFuture<Response> requestFuture) {
        timeoutChecker.schedule(() -> {
            if (!requestFuture.isDone()) {
                logger.error("Request timed out. Maximum processing time exceeded");
                requestFuture.complete(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            }
        }, httpRequestTimeoutInMillis, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Response> handleLeaderRequest(Request request, RequestParameters parameters) {
        String entryId = parameters.key();
        int from = parameters.from();
        int ack = parameters.ack();

        List<String> nodeUrls = shardingAlgorithm.getNodesByKey(entryId, from);
        Map<String, HttpRequest> requests = Utils.buildRequests(request, nodeUrls, entryId);
        List<CompletableFuture<Response>> requestsFutures = getRequestsFutures(requests, nodeUrls);

        if (requests.get(selfUrl) != null) {
            requestsFutures.add(safetyHandleRequestFuture(request, entryId));
        }

        return getWaitQuorumFuture(request, from, ack, requestsFutures);
    }

    private List<CompletableFuture<Response>> getRequestsFutures(Map<String, HttpRequest> requests,
                                                                 List<String> nodeUrls) {
        List<CompletableFuture<Response>> responses = new ArrayList<>();
        for (String nodeUrl : nodeUrls) {
            HttpRequest request = requests.get(nodeUrl);
            if (!Objects.equals(selfUrl, nodeUrl)) {
                responses.add(sendRequestToProxyAsync(request, nodeUrl));
            }
        }
        return responses;
    }

    private CompletableFuture<Response> sendRequestToProxyAsync(HttpRequest httpRequest, String nodeUrl) {
//        logger.debug("[%s] Send request to node [%s]: %s %s".formatted(selfUrl, nodeUrl, httpRequest.method(),
//                httpRequest.uri()));

        final CompletableFuture<Response> sendRequestFuture = new CompletableFuture<>();
        httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((httpResponse, throwable) -> {
                            if (throwable != null) {
                                logger.error(throwable.getMessage());
                                sendRequestFuture.completeExceptionally(throwable);
                                return;
                            }
                            sendRequestFuture.complete(processProxyResponse(httpResponse));
                        }
                );

        return sendRequestFuture;
    }

    private CompletableFuture<Response> getWaitQuorumFuture(Request request, int from, int ack,
                                                            List<CompletableFuture<Response>> requestsFutures) {
        List<Response> positiveResponses = new ArrayList<>();
        CompletableFuture<Response> waitQuorumFuture = new CompletableFuture<>();
        AtomicInteger remainingFailures = new AtomicInteger(from - ack + 1);
        AtomicInteger remainingAcks = new AtomicInteger(ack);

        for (CompletableFuture<Response> requestFuture : requestsFutures) {
            requestFuture.whenComplete((response, throwable) -> {
                boolean positiveResponse = throwable == null
                        || (response != null && response.getStatus() < Constants.SERVER_ERRORS);
                if (positiveResponse) {
                    remainingAcks.decrementAndGet();
                    positiveResponses.add(response);
                } else {
                    remainingFailures.decrementAndGet();
                }

                if (remainingAcks.get() <= 0) {
                    processResponse(request, positiveResponses, waitQuorumFuture);
                } else if (remainingFailures.get() == 0) {
                    waitQuorumFuture.complete(new Response(Constants.NOT_ENOUGH_REPLICAS, Response.EMPTY));
                }
            });
        }
        return waitQuorumFuture;
    }

    private static void processResponse(Request request, List<Response> positiveResponses,
                                        CompletableFuture<Response> waitQuorumFuture) {
        if (request.getMethod() == Request.METHOD_GET) {
            positiveResponses.sort(Comparator.comparingLong(r ->
                    Long.parseLong(r.getHeader(Constants.NIO_TIMESTAMP_HEADER))));
            waitQuorumFuture.complete(positiveResponses.getLast());
        } else {
            waitQuorumFuture.complete(positiveResponses.getFirst());
        }
    }

    private CompletableFuture<Response> safetyHandleRequestFuture(Request request, String entryId) {
        final CompletableFuture<Response> handleLocalRequestFuture = new CompletableFuture<>();
        workerPool.execute(() -> {
//            logger.debug("[%s] Process request: %s %s".formatted(selfUrl, request.getMethodName(),
//                    request.getURI()));
            try {
                handleLocalRequestFuture.complete(requestHandler.handle(request, entryId));
            } catch (Exception e) {
                logger.error("Error while processing request", e);
                handleLocalRequestFuture.completeExceptionally(e);
            }
        });
        return handleLocalRequestFuture;
    }
}
