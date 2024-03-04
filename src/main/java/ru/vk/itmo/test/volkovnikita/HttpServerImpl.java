package ru.vk.itmo.test.volkovnikita;

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
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    static final Set<String> METHODS = Set.of(
            "GET",
            "PUT",
            "DELETE"
    );

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
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

    @Path(value = "/v0/status")
    public Response status() {
        return Response.ok("It's okay (c) Tinkoff");
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getEntry(@Param(value = "id", required = true) String id) {
        if (isIdUncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        Entry<MemorySegment> entry = dao.get(key);
        return entry == null ? new Response(Response.NOT_FOUND, Response.EMPTY) :
                new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response up(@Param(value = "id", required = true) String id, Request request) {
        if (isIdUncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntry(@Param(value = "id", required = true) String id) {
        if (isIdUncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (METHODS.contains(request.getMethodName())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
            session.sendResponse(response);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            session.sendResponse(response);
        }
        session.sendResponse(response);
    }

    private boolean isIdUncorrect(String id) {
        return id == null || id.isEmpty() || id.isBlank();
    }
}
