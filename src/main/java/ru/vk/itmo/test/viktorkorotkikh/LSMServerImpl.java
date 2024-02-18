package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
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
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.LSMDaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class LSMServerImpl extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(LSMServerImpl.class);
    private static final long FLUSH_THRESHOLD = 1 << 20; // 1 MB
    private final ServiceConfig serviceConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

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
        log.info("Server started on port: " + serviceConfig.selfPort());
        log.info("Dao working dir: " + serviceConfig.workingDir());
    }

    public void stopServer() {
        stop();
        closeLSMDao();
    }

    @Path("/v0/entity")
    public void handleEntityRequest(Request request, HttpSession session) throws IOException {
        // validate id parameter
        String id = request.getParameter("id");
        if (id == null || id.length() <= 1) { // request.getParameter("id") returns "=" on empty parameter
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        switch (request.getMethod()) {
            case METHOD_GET -> session.sendResponse(handleGetEntity(id));
            case METHOD_PUT -> session.sendResponse(handlePutEntity(id, request));
            case METHOD_DELETE -> session.sendResponse(handleDeleteEntity(id));
            default -> session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

    private Response handleGetEntity(final String id) {
        Entry<MemorySegment> entry = dao.get(fromString(id));
        if (entry == null || entry.value() == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response handlePutEntity(final String id, Request request) {
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

    private Response handleDeleteEntity(final String id) {
        Entry<MemorySegment> newEntry = new BaseEntry<>(
                fromString(id),
                null
        );
        dao.upsert(newEntry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path("/v0/compact")
    @RequestMethod(value = {METHOD_GET})
    public Response handleCompact() throws IOException {
        dao.compact();
        return new Response(Response.OK, Response.EMPTY);
    }

    private static MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }
}
