package ru.vk.itmo.test.proninvalentin;

import one.nio.http.HttpException;
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
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Server extends HttpServer {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final ExecutorService workerPool;
    private final ShardingAlgorithm shardingAlgorithm;
    private final HttpClient httpClient;

    private final String selfUrl;
    private final long requestMaxProcessingTime;
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private static final String METHOD_ADDRESS = "/v0/entity";
    private static final String ID_PARAMETER_NAME = "id=";
    private static final String REQUEST_PATH = METHOD_ADDRESS + "?" + ID_PARAMETER_NAME;
    private static final Set<Integer> SUPPORTED_HTTP_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );
    private static final Map<Integer, String> httpCodeMapping = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND
    );

    public Server(ServiceConfig config, ReferenceDao dao, WorkerPool workerPool,
                  ShardingAlgorithm shardingAlgorithm, ServerConfig serverConfig)
            throws IOException {
        super(createServerConfig(config));

        this.dao = dao;
        this.shardingAlgorithm = shardingAlgorithm;
        this.workerPool = workerPool.pool;
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(serverConfig.getMaxWorkersNumber()))
                .build();
        this.selfUrl = config.selfUrl();
        this.requestMaxProcessingTime = TimeUnit.MILLISECONDS.toNanos(serverConfig.getRequestTimeoutInMilliseconds());
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

    private static void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("Error while sending response", e);
        }
    }

    private static void sendExceptionInfo(HttpSession session, Exception exception) {
        try {
            String responseCode;
            if (exception.getClass().equals(TimeoutException.class)) {
                responseCode = Response.REQUEST_TIMEOUT;
            } else {
                responseCode = Response.INTERNAL_ERROR;
            }
            session.sendResponse(new Response(responseCode, exception.getMessage().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            logger.error("Error while sending exception info", e);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        int httpMethod = request.getMethod();
        Response response = isSupportedMethod(httpMethod)
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        sendResponse(session, response);
    }

    private boolean isSupportedMethod(int httpMethod) {
        return SUPPORTED_HTTP_METHODS.contains(httpMethod);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        try {
            long createdAt = System.nanoTime();
            workerPool.execute(() -> processRequest(request, session, createdAt));
        } catch (RejectedExecutionException e) {
            logger.error("New request processing task cannot be scheduled for execution", e);
            sendResponse(session, new Response(TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private void processRequest(Request request, HttpSession session, long createdAt) {
        boolean timeoutExpired = System.nanoTime() - createdAt > requestMaxProcessingTime;
        if (timeoutExpired) {
            sendResponse(session, new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        // To prevent sending unnecessary proxied requests to the nodes
        if (!hasHandler(request)) {
            handleDefault(request, session);
            return;
        }

        String parameter = request.getParameter(ID_PARAMETER_NAME);
        if (isNullOrEmpty(parameter)) {
            sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        try {
            String nodeUrl = shardingAlgorithm.getNodeByKey(parameter);
            if (Objects.equals(selfUrl, nodeUrl)) {
                logger.debug("["
                        + selfUrl
                        + "] Process request: "
                        + request.getMethodName()
                        + request.getURI());
                super.handleRequest(request, session);
            } else {
                logger.debug("["
                        + selfUrl
                        + "] Send request to node ["
                        + nodeUrl
                        + "]: "
                        + request.getMethodName()
                        + request.getURI());
                handleProxyRequest(request, session, nodeUrl, parameter);
            }
        } catch (Exception e) {
            logger.error("Error while processing request", e);

            String responseCode = e.getClass() == HttpException.class
                    ? Response.BAD_REQUEST
                    : Response.INTERNAL_ERROR;
            sendResponse(session, new Response(responseCode, Response.EMPTY));
        }
    }

    private boolean hasHandler(Request request) {
        return request.getURI().startsWith(REQUEST_PATH);
    }

    private void handleProxyRequest(Request request, HttpSession session, String nodeUrl, String parameter) {
        HttpRequest httpRequest;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(nodeUrl + REQUEST_PATH + parameter));
        switch (request.getMethod()) {
            case Request.METHOD_PUT -> {
                byte[] body = request.getBody();
                if (body == null) {
                    sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                    return;
                }
                httpRequest = builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            }
            case Request.METHOD_GET -> {
                httpRequest = builder.GET().build();
            }
            case Request.METHOD_DELETE -> {
                httpRequest = builder.DELETE().build();
            }
            default -> {
                sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
        }

        sendProxyRequest(session, httpRequest);
    }

    private void sendProxyRequest(HttpSession session, HttpRequest httpRequest) {
        try {
            HttpResponse<byte[]> httpResponse = httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .get(requestMaxProcessingTime, TimeUnit.NANOSECONDS);

            String statusCode = httpCodeMapping.getOrDefault(httpResponse.statusCode(), null);
            if (statusCode != null) {
                sendResponse(session, new Response(statusCode, httpResponse.body()));
            } else {
                logger.error("The proxied node returned a response with an unexpected status: "
                        + httpResponse.statusCode());
                sendResponse(session, new Response(Response.INTERNAL_ERROR, httpResponse.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(e.getMessage());
            sendExceptionInfo(session, e);
        } catch (ExecutionException e) {
            logger.error("Execution exception while processing the httpRequest", e);
            sendExceptionInfo(session, e);
        } catch (TimeoutException e) {
            logger.error("Request timed out. Maximum processing time exceeded", e);
            sendExceptionInfo(session, e);
        }
    }

    // region Handlers

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsert(@Param(value = "id", required = true) String id, Request request) {
        if (isNullOrEmpty(id) || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegmentFactory.fromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(required = true, value = "id") String id) {
        if (isNullOrEmpty(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegmentFactory.fromString(id);

        Entry<MemorySegment> entry = dao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        byte[] value = MemorySegmentFactory.toByteArray(entry.value());
        return Response.ok(value);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(required = true, value = "id") String id) {
        if (isNullOrEmpty(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> deletedMemorySegment = MemorySegmentFactory.toDeletedMemorySegment(id);
        dao.upsert(deletedMemorySegment);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private boolean isNullOrEmpty(String str) {
        return str == null || str.isEmpty();
    }

    // endregion
}
