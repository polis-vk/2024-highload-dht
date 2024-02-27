package ru.vk.itmo.test.tyapuevdmitrij;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class ServerImplementation extends HttpServer {

    private final MemorySegmentDao memorySegmentDao;

    private static final Logger logger = LoggerFactory.getLogger(ServerImplementation.class);

    private static final String ENTITY_PATH = "/v0/entity";

    public ServerImplementation(ServiceConfig config, MemorySegmentDao memorySegmentDao) throws IOException {
        super(createServerConfig(config));
        this.memorySegmentDao = memorySegmentDao;
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

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        Entry<MemorySegment> entry = memorySegmentDao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            logger.error("Exception in request method :", e);
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id") String id, Request request) {
        if (id == null || id.isEmpty() || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        byte[] requestBody = request.getBody();
        if (requestBody == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(requestBody));
        memorySegmentDao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)), null);
        memorySegmentDao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Path(ENTITY_PATH)
    public Response unsupportedMethods() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

}


