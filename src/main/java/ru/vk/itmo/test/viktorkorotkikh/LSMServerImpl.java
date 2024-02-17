package ru.vk.itmo.test.viktorkorotkikh;

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
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.LSMDaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class LSMServerImpl extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(LSMServerImpl.class);
    private static final long FLUSH_THRESHOLD = 1 << 20; // 1 MB
    private final ServiceConfig serviceConfig;
    private LSMDaoImpl dao;

    private static final List<Integer> SUPPORTED_METHODS = List.of(METHOD_GET, METHOD_PUT, METHOD_DELETE);

    // private static final String PATH = "/v0/entity";

    public LSMServerImpl(ServiceConfig serviceConfig) throws IOException {
        super(createServerConfig(serviceConfig));
        this.serviceConfig = serviceConfig;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{createAcceptorConfig(serviceConfig.selfPort())};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static AcceptorConfig createAcceptorConfig(int port) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        return acceptorConfig;
    }

    private void createLSMDao() {
        Config daoConfig = new Config(
                this.serviceConfig.workingDir(),
                FLUSH_THRESHOLD
        );
        dao = new LSMDaoImpl(daoConfig);
    }

    private void closeLSMDao() {
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void startServer() {
        createLSMDao();
        start();
        logger.info("Server started on port: 8080 (http)");
    }

    public void stopServer() {
        stop();
        logger.info("Server stopped. Closing dao...");
        closeLSMDao();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String id = request.getParameter("id");
        if (id == null || id.length() <= 1) { // request.getParameter("id") returns "=" on empty parameter
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }
        super.handleRequest(request, session);
    }

    @Path("/v0/entity")
    @RequestMethod(value = {METHOD_GET})
    public Response handleGet(@Param(value = "id", required = true) String id) {
        logger.info("Incoming request GET /v0/entity?id=" + id);

        byte[] byteKey = id.getBytes(StandardCharsets.UTF_8);
        Entry<MemorySegment> entry = dao.get(MemorySegment.ofArray(byteKey));
        if (entry == null || entry.value() == null) {
            return new Response(Response.NOT_FOUND, byteKey);
        }

        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(value = {METHOD_PUT})
    public Response handlePut(@Param(value = "id", required = true) String id, Request request) {
        logger.info("Incoming request PUT /v0/entity?id=" + id);

        if (request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> newEntry = new BaseEntry<>(
                fromString(id),
                MemorySegment.ofArray(request.getBody())
        );
        dao.upsert(newEntry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(value = {METHOD_DELETE})
    public Response handleDelete(@Param(value = "id", required = true) String id) {
        logger.info("Incoming request DELETE /v0/entity?id=" + id);

        Entry<MemorySegment> newEntry = new BaseEntry<>(
                fromString(id),
                null
        );
        dao.upsert(newEntry);
        var response = new Response(Response.ACCEPTED);
        response.addHeader("Content-Length: 0");

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (SUPPORTED_METHODS.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }

        session.sendResponse(response);
    }
}
