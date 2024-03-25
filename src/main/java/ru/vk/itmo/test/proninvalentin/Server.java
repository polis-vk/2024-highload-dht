package ru.vk.itmo.test.proninvalentin;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.proninvalentin.dao.ReferenceDao;
import ru.vk.itmo.test.proninvalentin.failure_limiter.FailureLimiter;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Server extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final ExecutorService workerPool;
    private final ShardingAlgorithm shardingAlgorithm;
    private final HttpClient httpClient;
    private final FailureLimiter failureLimiter;
    private final RequestHandler requestHandler;

    private final String selfUrl;
    private final List<String> clusterUrls;
    private final long requestMaxTimeToTakeInWorkInMillis;
    private final long httpRequestTimeoutInMillis;
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String METHOD_ADDRESS = "/v0/entity";
    private static final String ID_PARAMETER_NAME = "id=";
    private static final String REQUEST_PATH = METHOD_ADDRESS + "?" + ID_PARAMETER_NAME;
    public static final int SERVER_ERRORS = 500;
    public static final String FROM_PARAMETER_NAME = "from=";
    public static final String ACK_PARAMETER_NAME = "ack=";
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp:";
    private static final String HTTP_TIMESTAMP_HEADER = "X-Timestamp";
    private static final String TERMINATION_HEADER = "X-Termination";
    private static final String TERMINATION_TRUE = "true";

    public Server(ServiceConfig config, ReferenceDao dao, WorkerPool workerPool,
                  ShardingAlgorithm shardingAlgorithm, ServerConfig serverConfig,
                  FailureLimiter failureLimiter)
            throws IOException {
        super(createServerConfig(config));

        this.shardingAlgorithm = shardingAlgorithm;
        this.workerPool = workerPool.pool;
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(serverConfig.getMaxWorkersNumber()))
                .build();
        this.selfUrl = config.selfUrl();
        this.clusterUrls = config.clusterUrls();
        this.requestMaxTimeToTakeInWorkInMillis = serverConfig.getRequestMaxTimeToTakeInWorkInMillis();
        this.httpRequestTimeoutInMillis = serverConfig.getHttpRequestTimeoutInMillis();
        this.failureLimiter = failureLimiter;
        this.requestHandler = new RequestHandler(dao);
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

    private static void safetySendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("Error while sending response", e);
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

    private static Response handleExceptionInfo(Exception exception) {
        String responseCode;
        if (exception.getClass().equals(TimeoutException.class)) {
            responseCode = Response.REQUEST_TIMEOUT;
        } else {
            responseCode = Response.INTERNAL_ERROR;
        }
        return new Response(responseCode, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        int httpMethod = request.getMethod();
        Response response = Utils.isSupportedMethod(httpMethod)
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        safetySendResponse(session, response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        try {
            long createdAt = System.currentTimeMillis();
            workerPool.execute(() -> processRequest(request, session, createdAt));
        } catch (RejectedExecutionException e) {
            logger.error("New request processing task cannot be scheduled for execution", e);
            safetySendResponse(session, new Response(TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private void processRequest(Request request, HttpSession session, long createdAt) {
        boolean timeoutExpired = System.currentTimeMillis() - createdAt > requestMaxTimeToTakeInWorkInMillis;
        if (timeoutExpired) {
            safetySendResponse(session, new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        // To prevent sending unnecessary proxied requests to the nodes
        if (!hasHandler(request) || !Utils.isSupportedMethod(request.getMethod())) {
            handleDefault(request, session);
            return;
        }

        RequestParameters parameters = new RequestParameters(
                request.getParameter(ID_PARAMETER_NAME),
                request.getParameter(FROM_PARAMETER_NAME),
                request.getParameter(ACK_PARAMETER_NAME),
                clusterUrls.size()
        );

        if (!parameters.isValid()) {
            safetySendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        if (request.getHeader(TERMINATION_HEADER) == null) {
            safetySendResponse(session, handleLeaderRequest(request, parameters));
        } else {
            safetySendResponse(session, safetyHandleRequest(request, parameters.key()));
        }
    }

    private boolean hasHandler(Request request) {
        return request.getURI().startsWith(REQUEST_PATH);
    }

    private Response handleLeaderRequest(Request request, RequestParameters parameters) {
        String entryId = parameters.key();
        int from = parameters.from();
        int ack = parameters.ack();

        List<String> nodeUrls = shardingAlgorithm.getNodesByKey(entryId, from);
        if (nodeUrls.size() < from) {
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }

        HashMap<String, HttpRequest> requests = buildRequests(request, nodeUrls, entryId);
        List<Response> responses = waitResponses(requests, nodeUrls);

        if (requests.get(selfUrl) != null) {
            responses.add(safetyHandleRequest(request, entryId));
        }

        List<Response> positiveResponses = getPositiveResponses(responses);
        if (positiveResponses.size() >= ack) {
            if (request.getMethod() == Request.METHOD_GET) {
                positiveResponses.sort(Comparator.comparingLong(r -> Long.parseLong(r.getHeader(NIO_TIMESTAMP_HEADER))));
                return positiveResponses.getLast();
            } else {
                return positiveResponses.getFirst();
            }
        } else {
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
    }

    private List<Response> getPositiveResponses(List<Response> responses) {
        List<Response> positiveResponses = new ArrayList<>();
        for (Response response : responses) {
            if (response.getStatus() < SERVER_ERRORS) {
                positiveResponses.add(response);
            }
        }
        return positiveResponses;
    }

    private HashMap<String, HttpRequest> buildRequests(Request request, List<String> nodeUrls, String entryId) {
        HashMap<String, HttpRequest> httpRequests = new HashMap<>(nodeUrls.size());
        for (String nodeUrl : nodeUrls) {
            httpRequests.put(nodeUrl, buildProxyRequest(request, nodeUrl, entryId));
        }
        return httpRequests;
    }

    private List<Response> waitResponses(HashMap<String, HttpRequest> requests, List<String> nodeUrls) {
        List<Response> responses = new ArrayList<>();
        for (String nodeUrl : nodeUrls) {
            HttpRequest request = requests.get(nodeUrl);
            if (!Objects.equals(selfUrl, nodeUrl)) {
                responses.add(sendRequestToProxy(request, nodeUrl));
            }
        }
        return responses;
    }

    private Response sendRequestToProxy(HttpRequest request, String nodeUrl) {
        logger.debug("[%s] Send request to node [%s]: %s %s".formatted(selfUrl, nodeUrl, request.method(),
                request.uri()));

        if (failureLimiter.readyForRequests(nodeUrl)) {
            return handleProxyRequest(request, nodeUrl);
        } else {
            logger.warn("[%s] Can't send request to closed node [%s]: %s %s".formatted(selfUrl, nodeUrl,
                    request.method(), request.uri()));
            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
        }
    }

    private HttpRequest buildProxyRequest(Request request, String nodeUrl, String parameter) {
        byte[] body = request.getBody();
        return HttpRequest.newBuilder(URI.create(nodeUrl + REQUEST_PATH + parameter))
                .method(request.getMethodName(), body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .setHeader(TERMINATION_HEADER, TERMINATION_TRUE)
                .build();
    }

    private Response handleProxyRequest(HttpRequest httpRequest, String nodeUrl) {
        try {
            HttpResponse<byte[]> httpResponse = httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .get(httpRequestTimeoutInMillis, TimeUnit.MILLISECONDS);

            String statusCode = Utils.httpCodeMapping.getOrDefault(httpResponse.statusCode(), null);
            if (statusCode == null) {
                logger.error("The proxied node returned a response with an unexpected status: "
                        + httpResponse.statusCode());
                return new Response(Response.INTERNAL_ERROR, httpResponse.body());
            } else {
                if (httpResponse.statusCode() >= SERVER_ERRORS) {
                    failureLimiter.handleFailure(nodeUrl);
                }

                Response response = new Response(statusCode, httpResponse.body());
                long timestamp = httpResponse.headers().firstValueAsLong(HTTP_TIMESTAMP_HEADER).orElse(0);
                response.addHeader(NIO_TIMESTAMP_HEADER + timestamp);
                return response;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(e.getMessage());
            failureLimiter.handleFailure(nodeUrl);
            return handleExceptionInfo(e);
        } catch (ExecutionException e) {
            logger.error("Execution exception while processing the httpRequest", e);
            failureLimiter.handleFailure(nodeUrl);
            return handleExceptionInfo(e);
        } catch (TimeoutException e) {
            logger.error("Request timed out. Maximum processing time exceeded", e);
            failureLimiter.handleFailure(nodeUrl);
            return handleExceptionInfo(e);
        }
    }
}
