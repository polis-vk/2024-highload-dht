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
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.tyapuevdmitrij.dao.DAOException;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class ServerImplementation extends HttpServer {

    private final MemorySegmentDao memorySegmentDao;

    private static final String ENTITY_PATH = "/v0/entity";

    public ServerImplementation(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        memorySegmentDao = new MemorySegmentDao(new Config(config.workingDir(), 5242880));
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
    public Response get(@Param(value = "id", required = true) String id) {
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

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id, Request request) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(request.getBody()));
        memorySegmentDao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)), null);
        memorySegmentDao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    public void closeDAO() {
        try {
            memorySegmentDao.close();
        } catch (IOException e) {
            throw new DAOException("can't close DAO", e);
        }
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


