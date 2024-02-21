package ru.vk.itmo.test.dariasupriadkina;

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
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Server extends HttpServer {

    private final ReferenceDao dao;
    private final List<Integer> permittedMethods =
            List.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE);

    public Server(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createHttpServerConfig(config));
        this.dao = dao;
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();

        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        httpServerConfig.closeSessions = true;

        return httpServerConfig;
    }

    @Path("/health")
    @RequestMethod(Request.METHOD_GET)
    public Response health() {
        return Response.ok(Response.OK);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getHandler(@Param(value = "id", required = true) String id) {
        if (id.length() == 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = getEntryById(id);
        if (entry == null || entry.value() == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putHandler(Request request, @Param(value = "id", required = true) String id) {
        if (id.length() == 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        dao.upsert(convertToEntry(id, request.getBody()));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteHandler(@Param(value = "id", required = true) String id) {
        if (id.length() == 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        dao.upsert(convertToEntry(id, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (permittedMethods.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);

        }
        session.sendResponse(response);
    }

        private Entry<MemorySegment> convertToEntry(String id, byte[] body) {
        return new BaseEntry<>(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                convertByteArrToMemorySegment(body));
    }

    private MemorySegment convertByteArrToMemorySegment(byte[] bytes) {
        return bytes == null ? null : MemorySegment.ofArray(bytes);
    }

    private Entry<MemorySegment> getEntryById(String id) {
        return dao.get(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)));
    }
}
