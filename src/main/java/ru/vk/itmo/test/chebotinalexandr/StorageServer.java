package ru.vk.itmo.test.chebotinalexandr;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class StorageServer extends HttpServer {
    private static final String PATH = "/v0/entity";
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public StorageServer(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createConfig(config));
        this.dao = dao;
    }

    private static HttpServerConfig createConfig(ServiceConfig config) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = config.selfPort();

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Path("/hello")
    public Response hello() {
        return Response.ok("Hello, cruel world!".getBytes(StandardCharsets.UTF_8));
    }

    @Path(PATH)
    @RequestMethod(METHOD_GET)
    public Response get(@Param("id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(fromString(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        } else {
            return Response.ok(toBytes(entry.value()));
        }
    }

    @Path(PATH)
    @RequestMethod(METHOD_PUT)
    public Response upsert(@Param("id") String id, Request request) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                fromString(id),
                fromBytes(request.getBody())
        );
        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(PATH)
    @RequestMethod(METHOD_DELETE)
    public Response delete(@Param("id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                fromString(id),
                null
        );
        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(PATH)
    public Response post(@Param("id") String id) {
        //Post (in-place updating in LSM) is not supported
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    private static MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment fromBytes(byte[] bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private static byte[] toBytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }
}

