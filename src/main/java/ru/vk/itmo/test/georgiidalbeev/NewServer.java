package ru.vk.itmo.test.georgiidalbeev;

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
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class NewServer extends HttpServer {

    private final ReferenceDao dao;
    private static final long FLUSH_THRESHOLD = 5242880;
    private static final String path = "/v0/entity";

    public NewServer(ServiceConfig config) throws IOException {
        super(configureServer(config));
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD));
    }

    private static HttpServerConfig configureServer(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Path(path)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        MemorySegment key = validateId(id);
        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                key,
                MemorySegment.ofArray(request.getBody())
        );

        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(path)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id", required = true) String id) {
        MemorySegment key = validateId(id);
        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(path)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        MemorySegment key = validateId(id);
        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                key,
                null
        );

        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(path)
    public Response otherMethods() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    private MemorySegment validateId(String id) {
        return (id == null || id.isEmpty()) ? null : MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }

    public void terminate() {
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Error closing DAO: ", e);
        }
    }
}
