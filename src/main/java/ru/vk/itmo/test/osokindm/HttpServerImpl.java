package ru.vk.itmo.test.osokindm;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class HttpServerImpl extends HttpServer {

    private static final int MEMORY_LIMIT = 8388608;
    private DaoWrapper daoWrapper;
    private final java.nio.file.Path workingDir;

    private final Logger logger = LoggerFactory.getLogger(HttpServerImpl.class);

    public HttpServerImpl(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        workingDir = config.workingDir();
        daoWrapper = new DaoWrapper(new Config(config.workingDir(), MEMORY_LIMIT));
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
    public synchronized void start() {
        try {
            daoWrapper = new DaoWrapper(new Config(workingDir, MEMORY_LIMIT));
        } catch (IOException e) {
            logger.error("Error occurred while creating database");
        }

        super.start();
    }

    @Override
    public synchronized void stop() {
        try {
            daoWrapper.stop();
        } catch (IOException e) {
            logger.error("Error occurred while closing database");
        }
        super.stop();
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id=", required = true) String id) {
        Entry<MemorySegment> result = daoWrapper.get(id);
        if (result != null && result.value() != null) {
            return new Response(Response.OK, result.value().toArray(ValueLayout.JAVA_BYTE));
        }
        return new Response(Response.NOT_FOUND, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id=", required = true) String id) {
        daoWrapper.delete(id);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }


    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsert(@Param(value = "id=", required = true) String id, Request request) {
        daoWrapper.upsert(id, request);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_POST)
    public Response post() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String id = request.getParameter("id=");
        if (id != null && id.isEmpty()) {
            handleDefault(request, session);
        } else {
            super.handleRequest(request, session);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

}
