package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.LSMDaoOutOfMemoryException;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.TooManyFlushesException;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMConstantResponse;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMCustomSession;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMServerResponseWithMemorySegment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class LSMServerImpl extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(LSMServerImpl.class);
    private static final String ENTITY_V0_PATH_WITH_ID_PARAM = "/v0/entity?id=";
    private static final int CLUSTER_REQUEST_TIMEOUT_MILLISECONDS = 10_000;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private final ConsistentHashingManager consistentHashingManager;
    private final String selfUrl;

    private final HttpClient clusterClient;

    public LSMServerImpl(
            ServiceConfig serviceConfig,
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ExecutorService executorService,
            ConsistentHashingManager consistentHashingManager
    ) throws IOException {
        super(createServerConfig(serviceConfig));
        this.dao = dao;
        this.executorService = executorService;
        this.consistentHashingManager = consistentHashingManager;
        this.selfUrl = serviceConfig.selfUrl();
        ThreadPoolExecutor clusterExecutor = new ThreadPoolExecutor(
                16,
                16,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new CustomThreadFactory("cluster-worker", true),
                new ThreadPoolExecutor.AbortPolicy()
        );
        clusterExecutor.prestartAllCoreThreads();
        this.clusterClient = HttpClient.newBuilder()
                .executor(clusterExecutor)
                .build();
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{createAcceptorConfig(serviceConfig.selfPort())};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static AcceptorConfig createAcceptorConfig(int port) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        return acceptorConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    final String path = request.getPath();
                    if (path.startsWith("/v0/entity")) {
                        handleEntityRequest(request, session);
                    } else {
                        super.handleRequest(request, session);
                    }
                } catch (Exception e) {
                    log.error("Unexpected error occurred: ", e);
                    sendResponseAndCloseSessionOnError(session, LSMConstantResponse.SERVICE_UNAVAILABLE_CLOSE);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Request was rejected");
            session.sendResponse(LSMConstantResponse.tooManyRequests(request));
        }
    }

    private static void sendResponseAndCloseSessionOnError(final HttpSession session, final Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException ex) {
            log.error("I/O error occurred when sending response");
            session.scheduleClose();
        }
    }

    @Path("/v0/entity")
    public void handleEntityRequest(Request request, HttpSession session) throws IOException {
        // validate id parameter
        final String id = request.getParameter("id=");
        if (id == null || id.isEmpty()) {
            log.info("Bad request: empty id parameter");
            session.sendResponse(LSMConstantResponse.badRequest(request));
            return;
        }
        final byte[] key = id.getBytes(StandardCharsets.UTF_8);
        final String server = consistentHashingManager.getServerByKey(key);
        final boolean isClusterRequest = !server.equals(selfUrl);

        if (isClusterRequest) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(server + ENTITY_V0_PATH_WITH_ID_PARAM + id))
                    .header("cluster-url", selfUrl);
            switch (request.getMethod()) {
                case METHOD_GET -> builder.GET();
                case METHOD_PUT -> {
                    final byte[] body = request.getBody();
                    if (body == null) {
                        log.info("PUT bad request: empty body");
                        session.sendResponse(LSMConstantResponse.badRequest(request));
                        return;
                    }
                    builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
                }
                case METHOD_DELETE -> builder.DELETE();
                default -> {
                    session.sendResponse(LSMConstantResponse.methodNotAllowed(request));
                    return;
                }
            }
            sendClusterRequest(request, builder.build(), session);
            return;
        }

        Response response = switch (request.getMethod()) {
            case METHOD_GET -> handleGetEntity(request, key, id);
            case METHOD_PUT -> handlePutEntity(request, key);
            case METHOD_DELETE -> handleDeleteEntity(request, key);
            default -> LSMConstantResponse.methodNotAllowed(request);
        };
        session.sendResponse(response);
    }

    private void sendClusterRequest(
            final Request originalRequest,
            final HttpRequest request,
            final HttpSession session
    ) {
        try {
            HttpResponse<byte[]> clusterResponse = clusterClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .get(CLUSTER_REQUEST_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
            if (clusterResponse.body() == null || clusterResponse.body().length == 0) { // all responses except 200 GET
                final int statusCode = clusterResponse.statusCode();
                Response response = switch (statusCode) {
                    case HTTP_OK -> LSMConstantResponse.ok(originalRequest);
                    case HTTP_CREATED -> LSMConstantResponse.created(originalRequest);
                    case HTTP_ACCEPTED -> LSMConstantResponse.accepted(originalRequest);
                    case HTTP_BAD_REQUEST -> LSMConstantResponse.badRequest(originalRequest);
                    case HTTP_NOT_FOUND -> LSMConstantResponse.notFound(originalRequest);
                    case HTTP_ENTITY_TOO_LARGE -> LSMConstantResponse.entityTooLarge(originalRequest);
                    case 429 -> LSMConstantResponse.tooManyRequests(originalRequest);
                    case HTTP_UNAVAILABLE -> LSMConstantResponse.serviceUnavailable(originalRequest);
                    case HTTP_GATEWAY_TIMEOUT -> LSMConstantResponse.gatewayTimeout(originalRequest);
                    default -> {
                        log.error("Failed to determine response from cluster by status code {}", statusCode);
                        yield LSMConstantResponse.serviceUnavailable(originalRequest);
                    }
                };
                sendResponseAndCloseSessionOnError(session, response);
            } else { // response with body - 200 GET
                sendResponseAndCloseSessionOnError(session, Response.ok(clusterResponse.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Current thread was interrupted during processing request to cluster");
            sendResponseAndCloseSessionOnError(session, LSMConstantResponse.SERVICE_UNAVAILABLE_CLOSE);
        } catch (ExecutionException e) {
            log.error("Unexpected exception occurred", e);
            sendResponseAndCloseSessionOnError(session, LSMConstantResponse.SERVICE_UNAVAILABLE_CLOSE);
        } catch (TimeoutException e) {
            log.warn("Request timeout to cluster server {}", request.uri().getPath());
            sendResponseAndCloseSessionOnError(session, LSMConstantResponse.gatewayTimeout(originalRequest));
        }
    }

    private Response handleGetEntity(final Request request, final byte[] id, final String idString) {
        final Entry<MemorySegment> entry;
        try {
            entry = dao.get(fromByteArray(id));
        } catch (UncheckedIOException e) {
            // sstable get method throws UncheckedIOException
            log.error("Unexpected UncheckedIOException occurred", e);
            return LSMConstantResponse.serviceUnavailable(request);
        }
        if (entry == null || entry.value() == null) {
            log.info("Entity(id={}) was not found", idString);
            return LSMConstantResponse.notFound(request);
        }

        return new LSMServerResponseWithMemorySegment(Response.OK, entry.value());
    }

    private Response handlePutEntity(final Request request, final byte[] id) {
        if (request.getBody() == null) {
            log.info("PUT bad request: empty body");
            return LSMConstantResponse.badRequest(request);
        }

        Entry<MemorySegment> newEntry = new BaseEntry<>(
                fromByteArray(id),
                MemorySegment.ofArray(request.getBody())
        );
        try {
            dao.upsert(newEntry);
        } catch (LSMDaoOutOfMemoryException e) {
            // when entry is too big to be putted into memtable
            log.info("Entity(id={}) is too big to be putted into memtable", id);
            return LSMConstantResponse.entityTooLarge(request);
        } catch (TooManyFlushesException e) {
            // when one memory table is in the process of being flushed, and the second is already full
            log.warn("Too many flushes");
            return LSMConstantResponse.tooManyRequests(request);
        }

        return LSMConstantResponse.created(request);
    }

    private Response handleDeleteEntity(final Request request, final byte[] id) {
        final Entry<MemorySegment> newEntry = new BaseEntry<>(
                fromByteArray(id),
                null
        );
        try {
            dao.upsert(newEntry);
        } catch (LSMDaoOutOfMemoryException e) {
            // when entry is too big to be putted into memtable
            log.info("Entity(id={}) is too big to be putted into memtable", id);
            return LSMConstantResponse.entityTooLarge(request);
        } catch (TooManyFlushesException e) {
            // when one memory table is in the process of being flushed, and the second is already full
            log.warn("Too many flushes");
            return LSMConstantResponse.tooManyRequests(request);
        }

        return LSMConstantResponse.accepted(request);
    }

    @Path("/v0/compact")
    @RequestMethod(value = {METHOD_GET})
    public Response handleCompact(Request request) throws IOException {
        dao.compact();
        return LSMConstantResponse.ok(request);
    }

    private static MemorySegment fromByteArray(final byte[] data) {
        return MemorySegment.ofArray(data);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(LSMConstantResponse.badRequest(request));
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new LSMCustomSession(socket, this);
    }
}
