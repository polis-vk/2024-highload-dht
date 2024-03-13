package ru.vk.itmo.test.reference;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public class ReferenceServer extends HttpServer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceServer.class);

    private final Executor executor;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public ReferenceServer(ServiceConfig config,
                           Executor executor,
                           Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createServerConfig(config));
        this.executor = executor;
        this.dao = dao;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        handleAsync(session, () -> super.handleRequest(request, session));
    }

    private void handleAsync(HttpSession session, ERunnable runnable) throws IOException {
        try {
            executor.execute(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    try {
                        session.sendError(Response.INTERNAL_ERROR, null);
                    } catch (IOException ex) {
                        log.error("Exception while sending close connection", e);
                        session.scheduleClose();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Workers pool queue overflow", e);
            session.sendError(Response.SERVICE_UNAVAILABLE, null);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path("/v0/entity")
    public Response entity(Request request, @Param(value = "id") String id) {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                Entry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                }

                return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            }
            case Request.METHOD_PUT -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new BaseEntry<>(key, value));
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                dao.upsert(new BaseEntry<>(key, null));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private interface ERunnable {
        void run() throws Exception;
    }

}
