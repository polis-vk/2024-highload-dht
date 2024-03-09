package ru.vk.itmo.test.elenakhodosova;

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
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String PATH_NAME = "/v0/entity";

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
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

    private boolean isParamIncorrect(String param) {
        return param == null || param.isEmpty();
    }
}
