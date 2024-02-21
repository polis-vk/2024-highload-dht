package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpServer;
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
import static one.nio.http.Request.METHOD_PUT;

public class Server extends HttpServer implements Closeable {

    private static final String PREFIX = "/v0/entity";
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    private final Response NOT_FOUND = new Response(Response.NOT_FOUND, Response.EMPTY);
    private final Response CREATED = new Response(Response.CREATED, Response.EMPTY);
    private final Response ACCEPTED = new Response(Response.ACCEPTED, Response.EMPTY);

    public Server(DaoServerConfig config) throws IOException {
        super(config);
        dao = new DaoImpl(mapConfig(config));
    }

    @Path(PREFIX)
    public Response getEntity(@Param(value = "id", required = true) String entityId) {
        Entry<MemorySegment> entity = dao.get(fromString(entityId));
        if (entity == null) {
            return NOT_FOUND;
        }
        return Response.ok(entity.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(PREFIX)
    @RequestMethod(METHOD_PUT)
    public Response createEntity(
            @Param(value = "id", required = true) String entityId,
            Request request
    ) {
        dao.upsert(
                makeEntry(fromString(entityId), MemorySegment.ofArray(request.getBody()))
        );
        return CREATED;
    }

    @Path(PREFIX)
    @RequestMethod(METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String entityId) {
        dao.upsert(
                makeEntry(fromString(entityId), null)
        );
        return ACCEPTED;
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
}
