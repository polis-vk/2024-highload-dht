package ru.vk.itmo.test.tveritinalexandr;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.tveritinalexandr.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class ServerImpl extends HttpServer {
    private static final String PATH_V0_ENTITY = "/v0/entity";
    private static final Response RESPONSE_NOT_FOUND = new Response(Response.NOT_FOUND, Response.EMPTY);
    private static final Response RESPONSE_BAD_REQUEST = new Response(Response.BAD_REQUEST, Response.EMPTY);
    private static final Response RESPONSE_CREATED = new Response(Response.CREATED, Response.EMPTY);
    private static final Response RESPONSE_ACCEPTED = new Response(Response.ACCEPTED, Response.EMPTY);
    private static final Response RESPONSE_NOT_ALLOWED = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);

    private final DaoImpl dao;

    public ServerImpl(HttpServerConfig config, DaoImpl dao) throws IOException {
        super(config);
        this.dao = dao;
    }

    @Path(PATH_V0_ENTITY)
    @RequestMethod(METHOD_GET)
    public Response getById(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) return RESPONSE_BAD_REQUEST;
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));

        Entry<MemorySegment> resultEntry = dao.get(key);

        if (resultEntry == null || resultEntry.value() == null) {
            return RESPONSE_NOT_FOUND;
        }

        return Response.ok(resultEntry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(PATH_V0_ENTITY)
    @RequestMethod(METHOD_PUT)
    public Response upsert(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty()) return RESPONSE_BAD_REQUEST;
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        MemorySegment value = MemorySegment.ofArray(request.getBody());

        dao.upsert(new BaseEntry<>(key, value));

        return RESPONSE_CREATED;
    }

    @Path(PATH_V0_ENTITY)
    @RequestMethod(METHOD_DELETE)
    public Response deleteById(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) return RESPONSE_BAD_REQUEST;
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));

        dao.upsert(new BaseEntry<>(key, null));

        return RESPONSE_ACCEPTED;
    }

    @Path(PATH_V0_ENTITY)
    public Response notAllowed() {
        return RESPONSE_NOT_ALLOWED;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(RESPONSE_BAD_REQUEST);
    }
}
