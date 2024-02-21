package ru.vk.itmo.test.solnyshkoksenia;

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
import ru.vk.itmo.test.solnyshkoksenia.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class MyHttpServer extends HttpServer {
    private final DaoImpl dao;

    public MyHttpServer(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        this.dao = new DaoImpl(createConfig(config));
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static Config createConfig(ServiceConfig config) {
        return new Config(config.workingDir(), Math.round(0.33 * 128 * 1024 * 1024)); // 0.33 * 128mb
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        if (request.getMethod() == Request.METHOD_GET) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        }
        session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
    }

    @Override
    public synchronized void stop() {
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        super.stop();
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public void get(final HttpSession session,
                    @Param(value = "id", required = true) String id) throws IOException {
        if (sendResponseIfEmpty(id, session)) {
            return;
        }

        Entry<MemorySegment> entry = dao.get(toMS(id));
        if (entry == null) {
            session.sendResponse(new Response(Response.NOT_FOUND, Response.EMPTY));
            return;
        }
        session.sendResponse(Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE)));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public void put(final Request request, final HttpSession session,
                    @Param(value = "id", required = true) String id) throws IOException {
        if (sendResponseIfEmpty(id, session)) {
            return;
        }
        dao.upsert(new BaseEntry<>(toMS(id), MemorySegment.ofArray(request.getBody())));
        session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public void delete(final HttpSession session,
                       @Param(value = "id", required = true) String id) throws IOException {
        if (sendResponseIfEmpty(id, session)) {
            return;
        }
        dao.upsert(new BaseEntry<>(toMS(id), null));
        session.sendResponse(new Response(Response.ACCEPTED, Response.EMPTY));
    }

    private boolean sendResponseIfEmpty(String input, final HttpSession session) throws IOException {
        if (input.isEmpty()) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return true;
        }
        return false;
    }

    private MemorySegment toMS(String input) {
        return MemorySegment.ofArray(input.getBytes(StandardCharsets.UTF_8));
    }
}
