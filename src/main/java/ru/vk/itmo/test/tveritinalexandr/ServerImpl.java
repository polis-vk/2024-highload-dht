package ru.vk.itmo.test.tveritinalexandr;

import one.nio.http.*;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.tveritinalexandr.dao.DaoImpl;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.*;

public class ServerImpl extends HttpServer {
    private static final String path = "/v0/entity";

    private DaoImpl dao;

    public ServerImpl(HttpServerConfig config, DaoImpl dao) throws IOException {
        super(config);
        this.dao = dao;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Path(path)
    @RequestMethod(METHOD_GET)
    public Response getById(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));

        Entry<MemorySegment> resultEntry = dao.get(key);

        if (resultEntry == null || resultEntry.value() == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(resultEntry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(path)
    @RequestMethod(METHOD_PUT)
    public Response upsert(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty()) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        MemorySegment value = MemorySegment.ofArray(request.getBody());

        dao.upsert(new BaseEntry<>(key, value));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(path)
    @RequestMethod(METHOD_DELETE)
    public Response deleteById(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));

        dao.upsert(new BaseEntry<>(key, null));

        return new Response(Response.ACCEPTED,  Response.EMPTY);
    }

    @Path(path)
    public Response notAllowed() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, one.nio.http.HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    public void setDao(DaoImpl dao) {
        this.dao = dao;
    }
}
