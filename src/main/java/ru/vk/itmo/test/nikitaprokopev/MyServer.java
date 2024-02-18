package ru.vk.itmo.test.nikitaprokopev;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.nikitaprokopev.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.*;
import static one.nio.http.Response.*;

public class MyServer extends HttpServer {

    private static final long FLUSH_THRESHOLD_BYTES = 2 * 1024 * 1024; // 2 MB
    private final ReferenceDao dao;

    public MyServer(ServiceConfig serviceConfig) throws IOException {
        super(createServerConfig(serviceConfig));
        dao = new ReferenceDao(new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD_BYTES));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    public void close() {
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id, Request request) {
        MemorySegment msKey = (id == null || id.isEmpty()) ? null : toMemorySegment(id);
        if (msKey == null) {
            return new Response(BAD_REQUEST, EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                toMemorySegment(request.getBody())
        );

        dao.upsert(entry);

        return new Response(CREATED, EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) {
        MemorySegment msKey = (id == null || id.isEmpty()) ? null : toMemorySegment(id);
        if (msKey == null) {
            return new Response(BAD_REQUEST, EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(msKey);

        if (entry == null) {
            return new Response(NOT_FOUND, EMPTY);
        }

        return Response.ok(toByteArray(entry.value()));
    }

    @Path("/v0/entity")
    @RequestMethod(METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        MemorySegment msKey = (id == null || id.isEmpty()) ? null : toMemorySegment(id);
        if (msKey == null) {
            return new Response(BAD_REQUEST, EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                null
        );

        dao.upsert(entry);

        return new Response(ACCEPTED, EMPTY);
    }

    // For other methods
    @Path("/v0/entity")
    public Response other() {
        return new Response(METHOD_NOT_ALLOWED, EMPTY);
    }

    private MemorySegment toMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private MemorySegment toMemorySegment(byte[] bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private byte[] toByteArray(MemorySegment ms) {
        return ms.toArray(ValueLayout.JAVA_BYTE);
    }

    // For other requests - 400 Bad Request
    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(BAD_REQUEST, EMPTY);
        session.sendResponse(response);
    }
}
