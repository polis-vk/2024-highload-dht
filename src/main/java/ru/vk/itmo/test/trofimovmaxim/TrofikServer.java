package ru.vk.itmo.test.trofimovmaxim;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.trofimovmaxim.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

public class TrofikServer extends HttpServer {
    private static final long FLUSH_THRESHOLD_BYTES = 42 * 1024 * 1024;
    private static final Response BAD_REQUEST = new Response(Response.BAD_REQUEST, Response.EMPTY);
    private static final Response INTERNAL_ERROR = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
    private static final Response TIMEOUT = new Response(Response.REQUEST_TIMEOUT, Response.EMPTY);
    public static final String ENDPOINT_V0_ENTITY_PREFIX = "/v0/entity?id=";
    private ReferenceDao dao;
    private final ServiceConfig config;
    private final DaoOperationsExecutor executor;
    private HttpClient httpClient;
    private final RendezvousBalancer balancer;

    public TrofikServer(ServiceConfig config) throws IOException {
        super(convertConfig(config));
        this.config = config;
        executor = new DaoOperationsExecutor();
        balancer = new RendezvousBalancer(config.clusterUrls());
    }

    private static HttpServerConfig convertConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(BAD_REQUEST);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String key = request.getParameter("id=");

        String clusterUrl = balancer.getCluster(key);
        if (clusterUrl == null) {
            session.sendResponse(BAD_REQUEST);
            return;
        }

        try {
            executor.run(() -> {
                try {
                    if (config.selfUrl().equals(clusterUrl)) {
                        handleLocal(request, session);
                        return;
                    }
                    handleRemote(request, session, clusterUrl, key);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendResponse(TIMEOUT);
        }
    }

    private void handleRemote(Request request, HttpSession session, String clusterUrl, String key) throws IOException {
        HttpRequest requestToCluster = HttpRequest.newBuilder(
                URI.create(clusterUrl + ENDPOINT_V0_ENTITY_PREFIX + key)
        ).method(
                request.getMethodName(),
                request.getMethod() == Request.METHOD_PUT
                        ? HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                        : HttpRequest.BodyPublishers.noBody()
        ).timeout(Duration.ofMillis(500)).build();
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    requestToCluster,
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            session.sendResponse(
                    new Response(responseByCode(
                            response.statusCode()
                    ), response.body())
            );
        } catch (IOException | InterruptedException e) {
            session.sendResponse(INTERNAL_ERROR);
            Thread.currentThread().interrupt();
        }
    }

    private void handleLocal(Request request, HttpSession session) throws IOException {
        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            session.sendResponse(INTERNAL_ERROR);
        }
    }

    @Override
    public synchronized void start() {
        try {
            dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        executor.start();
        httpClient = HttpClient.newHttpClient();
        super.start();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executor.stop();
        httpClient.close();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String responseByCode(int code) {
        return switch (code) {
            case 200 -> Response.OK;
            case 202 -> Response.ACCEPTED;
            case 201 -> Response.CREATED;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            case 404 -> Response.NOT_FOUND;
            case 400 -> Response.BAD_REQUEST;
            case 408 -> Response.REQUEST_TIMEOUT;
            default -> Response.INTERNAL_ERROR;
        };
    }

    private Entry<MemorySegment> entry(String key, byte[] value) {
        return new BaseEntry<>(memorySegmentFromString(key), value != null ? MemorySegment.ofArray(value) : null);
    }

    private MemorySegment memorySegmentFromString(String str) {
        return MemorySegment.ofArray(str.getBytes(StandardCharsets.UTF_8));
    }

    @Path("/v0/entity")
    public Response v0Entity(Request request,
                             @Param(value = "id", required = true) String id) {
        if (id.isEmpty()) {
            return BAD_REQUEST;
        }

        return switch (request.getMethod()) {
            case Request.METHOD_PUT -> entityPut(request, id);
            case Request.METHOD_GET -> entityGet(id);
            case Request.METHOD_DELETE -> entityDelete(id);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }

    public Response entityGet(String id) {
        Entry<MemorySegment> result = dao.get(memorySegmentFromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response entityPut(Request request, String id) {
        if (request.getBody() == null) {
            return BAD_REQUEST;
        }
        dao.upsert(entry(id, request.getBody()));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response entityDelete(String id) {
        dao.upsert(entry(id, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }
}
