package ru.vk.itmo.test.grunskiialexey;

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
import ru.vk.itmo.test.grunskiialexey.dao.MemorySegmentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class DaoServer extends HttpServer {
    private static final int FLUSH_THRESHOLD_BYTES = 65536;
    private static final String ENDPOINT = "/v0/entity";
    private final ServiceConfig config;
    private MemorySegmentDao dao;

    public DaoServer(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        this.config = config;
        loadDao();
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.selectors = 1;
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static MemorySegmentDao createDao(ServiceConfig config) throws IOException {
        return new MemorySegmentDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
    }

    @Path(ENDPOINT)
    @RequestMethod(Request.METHOD_GET)
    public Response handleGet(@Param(value = "id", required = true) String id) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        final Entry<MemorySegment> entry = dao.get(key);
        return (entry == null || entry.value() == null)
                ? new Response(Response.NOT_FOUND, Response.EMPTY)
                : Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(ENDPOINT)
    @RequestMethod(Request.METHOD_PUT)
    public Response handlePut(Request request, @Param(value = "id", required = true) String id) {
        if (id.isBlank() || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        final Entry<MemorySegment> entry = new BaseEntry<>(key, MemorySegment.ofArray(request.getBody()));
        dao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(ENDPOINT)
    @RequestMethod(Request.METHOD_DELETE)
    public Response handleDelete(@Param(value = "id", required = true) String id) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        final Entry<MemorySegment> entry = new BaseEntry<>(key, null);
        dao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        if (request.getPath().equals(ENDPOINT)) {
            session.sendError(Response.METHOD_NOT_ALLOWED, "Request must be: get, put, delete");
        } else {
            session.sendError(Response.BAD_REQUEST, "Incorrect request");
        }
    }

    public final void loadDao() throws IOException {
        dao = createDao(config);
    }

    public final void closeDao() throws IOException {
        dao.close();
    }
}
