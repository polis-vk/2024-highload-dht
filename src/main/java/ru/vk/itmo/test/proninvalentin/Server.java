package ru.vk.itmo.test.proninvalentin;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.RejectedSessionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.proninvalentin.dao.ReferenceDao;
import ru.vk.itmo.test.proninvalentin.request_parameter.RangeRequestParameters;
import ru.vk.itmo.test.proninvalentin.request_parameter.RequestParameters;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.utils.Constants;
import ru.vk.itmo.test.proninvalentin.utils.Utils;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Server extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    public static final String FUTURE_RETURN_VALUE_IGNORED = "FutureReturnValueIgnored";
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
    public void handleRequest(Request request, HttpSession classicSession) {
        if (!(classicSession instanceof CustomHttpSession session)) {
            throw new IllegalArgumentException("this method support only SafetyHttpSession");
        }
        try {
            long createdAt = System.currentTimeMillis();
            workerPool.execute(() -> processRequest(request, session, createdAt));
        } catch (RejectedExecutionException e) {
            logger.error("New request processing task cannot be scheduled for execution", e);
            session.safetySendResponse(new Response(Constants.TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    @SuppressWarnings(FUTURE_RETURN_VALUE_IGNORED)
    private void processRequest(Request request, CustomHttpSession session, long createdAt) {
        boolean timeoutExpired = System.currentTimeMillis() - createdAt > requestMaxTimeToTakeInWorkInMillis;
        if (timeoutExpired) {
            session.safetySendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        // To prevent sending unnecessary proxied requests to the nodes
        if (!Utils.hasHandler(request) || !Utils.isSupportedMethod(request.getMethod())) {
            handleDefault(request, session);
            return;
        }

        if (Utils.isRangeRequest(request)){
            handleRangeRequest(request, session);
        }else {
            handleSingleRequest(request, session);
        }
    }

    private void handleRangeRequest(Request request, CustomHttpSession session) {
        RangeRequestParameters params = new RangeRequestParameters(
                request.getParameter(Constants.START_REQUEST_PARAMETER_NAME),
                request.getParameter(Constants.END_REQUEST_PARAMETER_NAME)
        );

        if (!params.isValid()) {
            handleDefault(request, session);
            return;
        }

        session.safetySendResponse(requestHandler.getRange(params));
    }

    @SuppressWarnings(FUTURE_RETURN_VALUE_IGNORED)
    private void handleSingleRequest(Request request, CustomHttpSession session) {
        RequestParameters params = new RequestParameters(
                request.getParameter(Constants.ID_PARAMETER_NAME),
                request.getParameter(Constants.FROM_PARAMETER_NAME),
                request.getParameter(Constants.ACK_PARAMETER_NAME),
                clusterUrls.size()
        );

        if (!params.isValid()) {
            session.safetySendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        if (request.getHeader(Constants.TERMINATION_HEADER) == null) {
            CompletableFuture<Response> handleLeaderRequestFuture = handleLeaderRequest(request, params);
            handleLeaderRequestFuture.whenComplete((response, throwable) -> session.safetySendResponse(response));
            checkForTimeout(handleLeaderRequestFuture);
        } else {
            session.safetySendResponse(safetyHandleRequest(request, params.key()));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession sessionI) {
        if (!(sessionI instanceof CustomHttpSession session)) {
            throw new IllegalArgumentException("this method support only CustomHttpSession");
        }

        int httpMethod = request.getMethod();
        Response response = Utils.isSupportedMethod(httpMethod)
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        session.safetySendResponse(response);
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
        logger.debug("[%s] Process request: %s %s".formatted(selfUrl, request.getMethodName(),
                request.getURI()));
        try {
            return requestHandler.handle(request, entryId);
        } catch (Exception e) {
            logger.error("Error while processing request", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @SuppressWarnings(FUTURE_RETURN_VALUE_IGNORED)
    private void checkForTimeout(CompletableFuture<Response> requestFuture) {
        timeoutChecker.schedule(() -> {
            if (!requestFuture.isDone()) {
                logger.error("Request timed out. Maximum processing time exceeded");
                requestFuture.complete(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            }
        }, httpRequestTimeoutInMillis, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<Response> handleLeaderRequest(Request request, RequestParameters params) {
        String entryId = params.key();
        int from = params.from();
        int ack = params.ack();

        logger.debug("[%s] Handle leader request from/ack %d/%d".formatted(selfUrl, from, ack));
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

    @SuppressWarnings(FUTURE_RETURN_VALUE_IGNORED)
    private CompletableFuture<Response> sendRequestToProxyAsync(HttpRequest httpRequest, String nodeUrl) {
        logger.debug("[%s] Send request to node [%s]: %s %s".formatted(selfUrl, nodeUrl, httpRequest.method(),
                httpRequest.uri()));

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

    @SuppressWarnings(FUTURE_RETURN_VALUE_IGNORED)
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

                logger.debug("[%s] Remaining acks = %d ; Remaining failures = %d"
                        .formatted(selfUrl, remainingAcks.get(), remainingFailures.get()));
                if (remainingAcks.get() <= 0) {
                    mergeResponses(request, positiveResponses, waitQuorumFuture);
                } else if (remainingFailures.get() == 0) {
                    waitQuorumFuture.complete(new Response(Constants.NOT_ENOUGH_REPLICAS, Response.EMPTY));
                }
            });
        }
        return waitQuorumFuture;
    }

    private static void mergeResponses(Request request, List<Response> positiveResponses,
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
            logger.debug("[%s] Process request: %s %s".formatted(selfUrl, request.getMethodName(),
                    request.getURI()));
            try {
                handleLocalRequestFuture.complete(requestHandler.handle(request, entryId));
            } catch (Exception e) {
                logger.error("Error while processing request", e);
                handleLocalRequestFuture.completeExceptionally(e);
            }
        });
        return handleLocalRequestFuture;
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new CustomHttpSession(socket, this);
    }
}
