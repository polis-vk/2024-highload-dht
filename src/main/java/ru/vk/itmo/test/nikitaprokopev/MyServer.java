package ru.vk.itmo.test.nikitaprokopev;

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

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyServer extends HttpServer {
    private static final String BASE_PATH = "/v0/entity";
    private static final long MAX_RESPONSE_TIME = TimeUnit.SECONDS.toMillis(1);
    private final Logger log = LoggerFactory.getLogger(MyServer.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ThreadPoolExecutor workerPool;
    private final HttpClient[] httpClients;
    private final int NODE_ID;
    private final ServiceConfig serviceConfig;

    public MyServer(
            ServiceConfig serviceConfig,
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ThreadPoolExecutor workerPool,
            HttpClient[] httpClients,
            int nodeId
    ) throws IOException {
        super(createServerConfig(serviceConfig));
        this.dao = dao;
        this.workerPool = workerPool;
        this.httpClients = httpClients;
        this.NODE_ID = nodeId;
        this.serviceConfig = serviceConfig;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id, Request request) throws IOException {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        int targetNode = hash(id);
        if (targetNode != NODE_ID) {
            return proxyRequest(Methods.PUT, id, targetNode, request.getBody());
        }

        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                MemorySegment.ofArray(request.getBody())
        );

        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) throws IOException {

        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        int targetNode = hash(id);
        if (targetNode != NODE_ID) {
            return proxyRequest(Methods.GET, id, targetNode, Response.EMPTY);
        }

        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = dao.get(msKey);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(toByteArray(entry.value()));
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) throws IOException {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        int targetNode = hash(id);
        if (targetNode != NODE_ID) {
            return proxyRequest(Methods.DELETE, id, targetNode, Response.EMPTY);
        }

        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                null
        );

        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    // For other methods
    @Path(BASE_PATH)
    public Response other() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    // For other requests - 400 Bad Request
    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        long createdAt = System.currentTimeMillis();
        try {
            workerPool.execute(
                    () -> {
                        if (System.currentTimeMillis() - createdAt > MAX_RESPONSE_TIME) {
                            try {
                                session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
                            } catch (IOException e) {
                                log.error("Exception while sending close connection", e);
                                session.scheduleClose();
                            }
                            return;
                        }

                        try {
                            super.handleRequest(request, session);
                        } catch (Exception e) {
                            handleRequestException(session, e);
                        }
                    }
            );
        } catch (RejectedExecutionException e) {
            log.error("Workers pool queue overflow", e);
            session.sendError(CustomResponseCodes.TOO_MANY_REQUESTS.getCode(), null);
        }
    }

    private void handleRequestException(HttpSession session, Exception e) {
        log.error("Error while handling request", e);
        try {
            if (e instanceof HttpException) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            log.error("Exception while sending close connection", e);
            session.scheduleClose();
        }
    }

    private MemorySegment toMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toByteArray(MemorySegment ms) {
        return ms.toArray(ValueLayout.JAVA_BYTE);
    }

    private Response proxyResponse(HttpResponse<byte[]> response, byte[] body) {
        String responseCode = switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            default -> Response.INTERNAL_ERROR;
        };

        return new Response(responseCode, body);
    }

    private Response proxyRequest(Methods method, String id, int targetNode, byte[] body) throws IOException {
        int idHttpClient = targetNode > NODE_ID ? targetNode - 1 : targetNode;
        try {
            switch (method) {
                case PUT -> {
                    HttpResponse<byte[]> response = httpClients[idHttpClient].send(HttpRequest.newBuilder()
                            .uri(URI.create(STR."\{serviceConfig.clusterUrls().get(targetNode)}\{BASE_PATH}?id=\{id}"))
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(), HttpResponse.BodyHandlers.ofByteArray());
                    return proxyResponse(response, Response.EMPTY);
                }
                case GET -> {
                    HttpResponse<byte[]> response = httpClients[idHttpClient].send(HttpRequest.newBuilder()
                            .uri(URI.create(STR."\{serviceConfig.clusterUrls().get(targetNode)}\{BASE_PATH}?id=\{id}"))
                            .GET()
                            .build(), HttpResponse.BodyHandlers.ofByteArray());
                    return proxyResponse(response, response.body());
                }
                case DELETE -> {
                    HttpResponse<byte[]> response = httpClients[idHttpClient].send(HttpRequest.newBuilder()
                            .uri(URI.create(STR."\{serviceConfig.clusterUrls().get(targetNode)}\{BASE_PATH}?id=\{id}"))
                            .DELETE()
                            .build(), HttpResponse.BodyHandlers.ofByteArray());
                    return proxyResponse(response, Response.EMPTY);
                }
                default -> {
                    log.error("Unknown method");
                    return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
                }
            }
        } catch (InterruptedException e) {
            log.error("Exception while sending request", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private int hash(String id) {
        int maxValue = Integer.MIN_VALUE;
        int nodeId = 0;
        for (int i = 0; i < serviceConfig.clusterUrls().size(); i++) {
            int hash = (id + i).hashCode();
            if (hash > maxValue) {
                maxValue = hash;
                nodeId = i;
            }
        }
        return nodeId;
    }

    private enum Methods {
        PUT, GET, DELETE
    }
}
