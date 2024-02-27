package ru.vk.itmo.test.proninvalentin;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Set;

public class Server extends HttpServer {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final MemorySegmentFactory msFactory;
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final Set<Integer> SUPPORTED_HTTP_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );

    public Server(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createServerConfig(config));

        this.dao = dao;
        this.msFactory = new MemorySegmentFactory();
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        int httpMethod = request.getMethod();
        Response response = isSupportedMethod(httpMethod)
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        session.sendResponse(response);
    }

    private boolean isSupportedMethod(int httpMethod) {
        return SUPPORTED_HTTP_METHODS.contains(httpMethod);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            logger.error("Error while processing request", e);

            String responseCode = e.getClass() == HttpException.class
                    ? Response.BAD_REQUEST
                    : Response.INTERNAL_ERROR;
            sendResponse(session, new Response(responseCode, Response.EMPTY));
        }
    }

    private static void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("Error while sending response", e);
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
    }

    // region Handlers

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsert(@Param(value = "id", required = true) String id, Request request) {
        if (isNullOrEmpty(id) || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = msFactory.fromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(required = true, value = "id") String id) {
        if (isNullOrEmpty(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = msFactory.fromString(id);

        Entry<MemorySegment> entry = dao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        byte[] value = msFactory.toByteArray(entry.value());
        return Response.ok(value);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(required = true, value = "id") String id) {
        if (isNullOrEmpty(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> deletedMemorySegment = msFactory.toDeletedMemorySegment(id);
        dao.upsert(deletedMemorySegment);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private boolean isNullOrEmpty(String str) {
        return str == null || str.isEmpty();
    }

    // endregion
}
