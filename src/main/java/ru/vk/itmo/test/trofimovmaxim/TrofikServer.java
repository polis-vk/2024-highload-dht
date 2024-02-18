package ru.vk.itmo.test.trofimovmaxim;

import one.nio.http.*;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.trofimovmaxim.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class TrofikServer extends HttpServer {
    private static final long FLUSH_THRESHOLD_BYTES = 10 * 1024 * 1024;
    private ReferenceDao dao;
    private final ServiceConfig config;

    public TrofikServer(ServiceConfig config) throws IOException {
        super(convertConfig(config));
        this.config = config;
    }

    private static HttpServerConfig convertConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Override
    public synchronized void start() {
        try {
            dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        super.start();
    }

    @Override
    public synchronized void stop() {
        for (SelectorThread selector : selectors) {
            selector.selector.forEach(Session::close);
        }

        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        super.stop();
    }

    private Entry<MemorySegment> entry(String key, byte[] value) {
        return new BaseEntry<>(memorySegmentFromString(key), value != null ? MemorySegment.ofArray(value) : null);
    }

    private MemorySegment memorySegmentFromString(String str) {
        return MemorySegment.ofArray(str.getBytes(StandardCharsets.UTF_8));
    }

    @Path("/v0/entity")
    public Response v0Entity(Request request,
                             @Param(value = "id", required = true) String id) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        return switch (request.getMethod()) {
            case Request.METHOD_PUT -> entityPut(request, id);
            case Request.METHOD_GET -> entityGet(id);
            case Request.METHOD_DELETE -> entityDelete(id);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }

    public Response entityGet(String id) {
        Entry<MemorySegment> result = dao.get(memorySegmentFromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response entityPut(Request request, String id) {
        if (request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        dao.upsert(entry(id, request.getBody()));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response entityDelete(@Param(value = "id", required = true) String id) {
        dao.upsert(entry(id, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }
}
