package ru.vk.itmo.test.osipovdaniil;

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
import ru.vk.itmo.test.osipovdaniil.dao.ReferenceDao;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public class ServerImpl extends HttpServer {

    private static final String ENTITY_PATH = "/v0/entity";

    private final ReferenceDao dao;

    public ServerImpl(final ServiceConfig serviceConfig, ReferenceDao dao) throws IOException {
        super(createHttpServerConfig(serviceConfig));
        this.dao = dao;
    }

    private static HttpServerConfig createHttpServerConfig(final ServiceConfig serviceConfig) {
        final HttpServerConfig httpServerConfig = new HttpServerConfig();
        final AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;
        httpServerConfig.acceptors = new AcceptorConfig[] { acceptorConfig };
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    private boolean validateId(final String id) {
        return id != null && !id.isEmpty();
    }

    private Response requestHandle(final String id, final Function<MemorySegment, Response> request) {
        if (!validateId(id)) {
            return new Response(Response.BAD_REQUEST, ("invalid id: " + id).getBytes(StandardCharsets.UTF_8));
        }
        final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        return request.apply(key);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param("id") final String id) {
        return requestHandle(id,
                key -> {
                    final Entry<MemorySegment> entry = dao.get(key);
                    return entry == null
                            ? new Response(Response.NOT_FOUND, Response.EMPTY) :
                            Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
                });
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param("id") final String id) {
        return requestHandle(id,
                key -> {
                    dao.upsert(new BaseEntry<>(key, null));
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                });
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param("id") final String id, final Request value) {
        return requestHandle(id,
                key -> {
                    dao.upsert(new BaseEntry<>(key, MemorySegment.ofArray(value.getBody())));
                    return new Response(Response.CREATED, Response.EMPTY);
                });
    }

    @Override
    public void handleRequest(final Request request, final HttpSession session) throws IOException {
        Response response;
        if (!request.getPath().startsWith(ENTITY_PATH)) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
            session.sendResponse(response);
            return;
        }
        final int method = request.getMethod();
        if (method == Request.METHOD_GET) {
            response = get(request.getParameter("id="));
        } else if (method == Request.METHOD_DELETE) {
            response = delete(request.getParameter("id="));
        } else if (method == Request.METHOD_PUT) {
            response = put(request.getParameter("id="), request);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
        session.sendResponse(response);
    }
}
