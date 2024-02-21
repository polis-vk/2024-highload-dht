package ru.vk.itmo.test.kovalchukvladislav.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalchukvladislav.dao.MemorySegmentDaoFactory;

public class DaoHttpRequestHandler extends HttpServer {
    public static final int FLUSH_THRESHOLD = 1024;
    public static final String ENTITY_PATH = "/v0/entity";

    private final Config daoConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    public DaoHttpRequestHandler(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        this.daoConfig = createDaoConfig(config);
    }

    @Override
    public synchronized void start() {
        super.start();
        try {
            this.dao = MemorySegmentDaoFactory.createDao(daoConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            this.dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendError(Response.BAD_REQUEST, "Wrong request type");
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id") String id) {
        if (Utils.isBlank(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = dao.get(MemorySegmentDaoFactory.fromString(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id") String id, Request request) {
        if (Utils.isBlank(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegmentDaoFactory.fromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id") String id) {
        if (Utils.isBlank(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        dao.upsert(new BaseEntry<>(MemorySegmentDaoFactory.fromString(id), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_POST)
    public Response postEntry() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = serviceConfig.selfPort();

        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.closeSessions = true;
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        return serverConfig;
    }

    private static Config createDaoConfig(ServiceConfig serviceConfig) {
        return new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD);
    }
}
