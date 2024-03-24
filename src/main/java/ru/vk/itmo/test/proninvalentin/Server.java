package ru.vk.itmo.test.proninvalentin;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.proninvalentin.dao.Dao;
import ru.vk.itmo.test.proninvalentin.dao.ExtendedBaseEntry;
import ru.vk.itmo.test.proninvalentin.dao.ExtendedEntry;
import ru.vk.itmo.test.proninvalentin.dao.ReferenceDao;
import ru.vk.itmo.test.proninvalentin.failure_limiter.FailureLimiter;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

public class Server extends HttpServer {
    public static final int SERVER_ERRORS = 500;
    public static final String FROM_PARAMETER_NAME = "from=";
    public static final String ACK_PARAMETER_NAME = "ack=";
    private final Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao;
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final ExecutorService workerPool;
    private final ShardingAlgorithm shardingAlgorithm;
    private final HttpClient httpClient;
    private final FailureLimiter failureLimiter;

    private final String selfUrl;
    private final List<String> clusterUrls;
    private final long requestMaxTimeToTakeInWorkInMillis;
    private final long httpRequestTimeoutInMillis;
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String METHOD_ADDRESS = "/v0/entity";
    private static final String ID_PARAMETER_NAME = "id=";
    private static final String REQUEST_PATH = METHOD_ADDRESS + "?" + ID_PARAMETER_NAME;
    private static final String TIMESTAMP_HEADER = "X-Timestamp";
    private static final String TERMINATION_HEADER = "X-Termination";
    private static final String TERMINATION_TRUE = "true";

    public Server(ServiceConfig config, ReferenceDao dao, WorkerPool workerPool,
                  ShardingAlgorithm shardingAlgorithm, ServerConfig serverConfig,
                  FailureLimiter failureLimiter)
            throws IOException {
        super(createServerConfig(config));

        this.dao = dao;
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

    private void safetyHandleRequest(Request request, HttpSession session) {
        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            logger.error("Error while processing request", e);
            safetySendResponse(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    private static void sendExceptionInfo(HttpSession session, Exception exception) {
        String responseCode;
        if (exception.getClass().equals(TimeoutException.class)) {
            responseCode = Response.REQUEST_TIMEOUT;
        } else {
            responseCode = Response.INTERNAL_ERROR;
        }
        safetySendResponse(session, new Response(responseCode, Response.EMPTY));
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
        if (!hasHandler(request)) {
            handleDefault(request, session);
            return;
        }

        if (Objects.equals(request.getHeader(TERMINATION_HEADER), TERMINATION_TRUE)) {
            safetyHandleRequest(request, session);
        } else {
            handleLeaderRequest(request, session);
        }
    }

    private void handleLeaderRequest(Request request, HttpSession session) {
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

        String entryId = parameters.key();
        int from = parameters.from();
        int ack = parameters.ack();

        List<String> nodeUrls = shardingAlgorithm.getNodesByKey(entryId, from);
        if (nodeUrls.size() < from) {
            safetySendResponse(session, new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY));
        }

        requests = buildRequests(request, nodeUrls);
        responses = waitResponses(request, session, nodeUrls, entryId);

        if (responses.size < ack) {
            safetySendResponse(session, new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY));
        } else {
            actualResponse = getActualResponse(responses);
            safetySendResponse(session, actualResponse);
        }
    }

    private Object buildRequests(Request request, List<String> nodeUrls) {

    }

    private void waitResponses(Request request, HttpSession session, List<String> nodeUrls, String entryId) {
        for (String nodeUrl : nodeUrls) {
            if (Objects.equals(selfUrl, nodeUrl)) {
                logger.debug("[%s] Process request: %s%s".formatted(selfUrl, request.getMethodName(),
                        request.getURI()));
                safetyHandleRequest(request, session);
            } else {
                sendRequestToProxy(request, session, entryId, nodeUrl);
            }
        }
    }

    private void sendRequestToProxy(Request request, HttpSession session, String entryId, String nodeUrl) {
        logger.debug("[%s] Send request to node [%s]: %s%s".formatted(selfUrl, nodeUrl, request.getMethodName(),
                request.getURI()));

        if (failureLimiter.readyForRequests(nodeUrl)) {
            handleProxyRequest(request, session, nodeUrl, entryId);
        } else {
            logger.warn("[%s] Can't send request to closed node [%s]: %s%s".formatted(selfUrl, nodeUrl,
                    request.getMethodName(), request.getURI()));
            safetySendResponse(session, new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    private boolean hasHandler(Request request) {
        return request.getURI().startsWith(REQUEST_PATH);
    }

    private void handleProxyRequest(Request request, HttpSession session, String nodeUrl, String parameter) {
        byte[] body = request.getBody();
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(nodeUrl + REQUEST_PATH + parameter))
                .method(request.getMethodName(), body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .setHeader(TERMINATION_HEADER, TERMINATION_TRUE)
                .build();
        sendProxyRequest(session, httpRequest, nodeUrl);
    }

    private void sendProxyRequest(HttpSession session, HttpRequest httpRequest, String nodeUrl) {
        try {
            HttpResponse<byte[]> httpResponse = httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .get(httpRequestTimeoutInMillis, TimeUnit.MILLISECONDS);

            String statusCode = Utils.httpCodeMapping.getOrDefault(httpResponse.statusCode(), null);
            if (statusCode == null) {
                logger.error("The proxied node returned a response with an unexpected status: "
                        + httpResponse.statusCode());
                safetySendResponse(session, new Response(Response.INTERNAL_ERROR, httpResponse.body()));
            } else {
                if (httpResponse.statusCode() >= SERVER_ERRORS) {
                    failureLimiter.handleFailure(nodeUrl);
                }

                safetySendResponse(session, new Response(statusCode, httpResponse.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(e.getMessage());
            sendExceptionInfo(session, e);
            failureLimiter.handleFailure(nodeUrl);
        } catch (ExecutionException e) {
            logger.error("Execution exception while processing the httpRequest", e);
            sendExceptionInfo(session, e);
            failureLimiter.handleFailure(nodeUrl);
        } catch (TimeoutException e) {
            logger.error("Request timed out. Maximum processing time exceeded", e);
            sendExceptionInfo(session, e);
            failureLimiter.handleFailure(nodeUrl);
        }
    }

    // region Handlers

    @Path(METHOD_ADDRESS)
    @RequestMethod(Request.METHOD_PUT)
    public Response upsert(@Param(value = "id", required = true) String id, Request request) {
        if (Utils.isNullOrBlank(id) || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegmentFactory.fromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new ExtendedBaseEntry<>(key, value, System.currentTimeMillis()));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(METHOD_ADDRESS)
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(required = true, value = "id") String id) {
        if (Utils.isNullOrBlank(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegmentFactory.fromString(id);
        ExtendedEntry<MemorySegment> entry = dao.get(key);

        if (entry == null || entry.value() == null) {
            Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(TIMESTAMP_HEADER + (entry != null ? entry.timestamp() : 0));
            return response;
        }

        Response response = new Response(Response.OK, MemorySegmentFactory.toByteArray(entry.value()));
        response.addHeader(TIMESTAMP_HEADER + entry.timestamp());
        return response;
    }

    @Path(METHOD_ADDRESS)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(required = true, value = "id") String id) {
        if (Utils.isNullOrBlank(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        ExtendedEntry<MemorySegment> deletedMemorySegment =
                MemorySegmentFactory.toDeletedMemorySegment(id, System.currentTimeMillis());
        dao.upsert(deletedMemorySegment);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    // endregion
}
