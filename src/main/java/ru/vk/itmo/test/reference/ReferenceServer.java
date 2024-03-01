package ru.vk.itmo.test.reference;

import one.nio.http.*;
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
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class ReferenceServer extends HttpServer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceServer.class);

    private final Executor executor;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
//    private final Queue<Runnable> workersQueue;

//    private AtomicLong previousTime = new AtomicLong(System.currentTimeMillis());

    public ReferenceServer(ServiceConfig config,
                           Executor executor,
                           Queue<Runnable> workersQueue,
                           Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createServerConfig(config));
//        this.workersQueue = workersQueue;
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
//        long now = System.currentTimeMillis();
//        long previous = previousTime.getAndSet(now);
//        if (previous / 1000 < now / 1000) {
//            log.info("Current queue size = {}", workersQueue.size());
//        }

        try {
            executor.execute(() -> {
                try {
                    super.handleRequest(request, session);
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
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
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

}
