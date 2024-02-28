package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;
import ru.vk.itmo.test.kovalevigor.dao.DaoImpl;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class Server extends HttpServer implements Closeable {

    private static final String ENTITY = "/v0/entity";
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    private enum Responses {
        NOT_FOUND(Response.NOT_FOUND),
        CREATED(Response.CREATED),
        ACCEPTED(Response.ACCEPTED),
        BAD_REQUEST(Response.BAD_REQUEST),
        NOT_ALLOWED(Response.METHOD_NOT_ALLOWED);

        private final String responseCode;

        Responses(String responseCode) {
            this.responseCode = responseCode;
        }

        public Response toResponse() {
            return emptyResponse(responseCode);
        }
    }

    public Server(DaoServerConfig config) throws IOException {
        super(config);
        dao = new DaoImpl(mapConfig(config));
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(Responses.BAD_REQUEST.toResponse());
    }

    @Path({ENTITY})
    public void notAllowed(Request request, HttpSession session) throws IOException {
        session.sendResponse(Responses.NOT_ALLOWED.toResponse());
    }

    @Path(ENTITY)
    @RequestMethod(METHOD_GET)
    public Response getEntity(@Param(value = "id", required = true) String entityId) {
        if (entityId.isEmpty()) {
            return Responses.BAD_REQUEST.toResponse();
        }
        Entry<MemorySegment> entity = dao.get(fromString(entityId));
        if (entity == null) {
            return Responses.NOT_FOUND.toResponse();
        }
        return Response.ok(entity.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(ENTITY)
    @RequestMethod(METHOD_PUT)
    public Response createEntity(
            @Param(value = "id", required = true) String entityId,
            Request request
    ) {
        if (entityId.isEmpty()) {
            return Responses.BAD_REQUEST.toResponse();
        }
        dao.upsert(
                makeEntry(fromString(entityId), MemorySegment.ofArray(request.getBody()))
        );
        return Responses.CREATED.toResponse();
    }

    @Path(ENTITY)
    @RequestMethod(METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String entityId) {
        if (entityId.isEmpty()) {
            return Responses.BAD_REQUEST.toResponse();
        }
        dao.upsert(
                makeEntry(fromString(entityId), null)
        );
        return Responses.ACCEPTED.toResponse();
    }

    @Override
    public void close() throws IOException {
        dao.close();
    }

    private static Config mapConfig(DaoServerConfig config) {
        return new Config(
                config.basePath,
                config.flushThresholdBytes
        );
    }

    private static Entry<MemorySegment> makeEntry(MemorySegment key, MemorySegment value) {
        return new BaseEntry<>(key, value);
    }

    private static MemorySegment fromString(final String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(CHARSET));
    }

    private static Response emptyResponse(String resultCode) {
        return new Response(resultCode, Response.EMPTY);
    }
}
