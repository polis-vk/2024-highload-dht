package ru.vk.itmo.test.bazhenovkirill;

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
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.bazhenovkirill.dao.DaoImpl;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class ServerImpl extends HttpServer {

    private static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;

    private static final String API_ENDPOINT = "/v0/entity";

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public ServerImpl(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        dao = new DaoImpl(new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Path(API_ENDPOINT)
    public Response handleEntityRequst(Request request, @Param(value = "id") String id) {
        if (isInvalidId(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                return getEntityById(id);
            }
            case Request.METHOD_DELETE -> {
                return deleteEntityById(id);
            }
            case Request.METHOD_PUT -> {
                return putEntity(id, request);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private Response deleteEntityById(String id) {
        MemorySegment key = convertStringToMemorySegment(id);
        try {
            dao.upsert(new BaseEntry<>(key, null));
        } catch (IllegalStateException ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Response getEntityById(String id) {
        MemorySegment key = convertStringToMemorySegment(id);
        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response putEntity(String id, Request request) {
        MemorySegment key = convertStringToMemorySegment(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        try {
            dao.upsert(new BaseEntry<>(key, value));
        } catch (IllegalStateException ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            super.handleRequest(request, session);
        } catch (IOException ex) {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private boolean isInvalidId(String id) {
        return id == null || id.isBlank();
    }

    private MemorySegment convertStringToMemorySegment(String id) {
        return MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }
}
