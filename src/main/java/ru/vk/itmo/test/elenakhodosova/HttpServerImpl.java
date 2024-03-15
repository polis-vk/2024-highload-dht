package ru.vk.itmo.test.elenakhodosova;

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
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String PATH_NAME = "/v0/entity";
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private final ExecutorService executorService;
    private static final Logger logger = LoggerFactory.getLogger(HttpServerImpl.class);

    private final HttpClient client;
    private final String selfUrl;
    private final List<String> nodes;

    private enum AllowedMethods {
        GET, PUT, DELETE
    }

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao, ExecutorService executorService) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
        this.executorService = executorService;
        this.selfUrl = config.selfUrl();
        this.nodes = config.clusterUrls();
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> processRequest(request, session));
        } catch (RejectedExecutionException e) {
            logger.error("Request rejected", e);
            session.sendResponse(new Response(TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private void processRequest(Request request, HttpSession session) {
        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            logger.error("Unexpected error when processing request", e);
            sendError(session, e);
        }
    }

    private void sendError(HttpSession session, Exception e) {
        try {
            String responseCode = e.getClass() == HttpException.class ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
            logger.error("Send error", e);
            session.sendError(responseCode, null);
        } catch (Exception ex) {
            logger.error("Unexpected error when sending error", ex);
        }
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

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id", required = true) String id) {
        if (isParamIncorrect(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        String targetNode = hash(id);
        if (!Objects.equals(targetNode, selfUrl)) {
            return redirectRequest(AllowedMethods.GET, id, targetNode, new byte[0]);
        }
        try {
            Entry<MemorySegment> value = dao.get(MemorySegment.ofArray(Utf8.toBytes(id)));
            return value == null ? new Response(Response.NOT_FOUND, Response.EMPTY)
                    : Response.ok(value.value().toArray(ValueLayout.JAVA_BYTE));
        } catch (Exception ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        if (isParamIncorrect(id) || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        byte[] value = request.getBody();
        String targetNode = hash(id);
        if (!Objects.equals(targetNode, selfUrl)) {
            return redirectRequest(AllowedMethods.PUT, id, targetNode, value);
        }
        try {
            dao.upsert(new BaseEntry<>(
                    MemorySegment.ofArray(Utf8.toBytes(id)),
                    MemorySegment.ofArray(value)));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (Exception ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        if (isParamIncorrect(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        String targetNode = hash(id);
        if (!Objects.equals(targetNode, selfUrl)) {
            return redirectRequest(AllowedMethods.DELETE, id, targetNode, new byte[0]);
        }
        try {
            dao.upsert(new BaseEntry<>(MemorySegment.ofArray(Utf8.toBytes(id)), null));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (Exception ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(PATH_NAME)
    public Response methodNotSupported() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response badRequest = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(badRequest);
    }

    private Response redirectRequest(AllowedMethods method, String id, String targetNode, byte[] body) {
        try {
            HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(targetNode + PATH_NAME + "?id=" + id))
                    .method(method.name(), HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.of(500, ChronoUnit.MILLIS)
                    ).build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(getResponseByCode(response.statusCode()), response.body());
        } catch (InterruptedException | IOException e) {
            logger.error("Error during sending request", e);
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private boolean isParamIncorrect(String param) {
        return param == null || param.isEmpty();
    }

    private String hash(String id) {
        int maxValue = Integer.MIN_VALUE;
        String maxHashNode = null;
        for (String node : nodes) {
            int hash = (id + node).hashCode();
            if (hash > maxValue) {
                maxValue = hash;
                maxHashNode = node;
            }
        }
        return maxHashNode;
    }

    private String getResponseByCode(int code) {
        return switch (code) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            case 429 -> TOO_MANY_REQUESTS;
            default -> Response.INTERNAL_ERROR;
        };
    }
}
