package ru.vk.itmo.test.pavelemelyanov;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Utf8;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyServer extends HttpServer {
    private static final String V0_PATH = "/v0/entity";
    private static final String ID_PARAM = "id=";

    private final ReferenceDao dao;
    private final ExecutorService workersPool;

    public MyServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(configureServer(config));
        this.dao = dao;
        workersPool = configureWorkersPool();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        if (!request.getPath().equals(V0_PATH)) {
            sendResponse(
                    session,
                    new Response(Response.BAD_REQUEST, Response.EMPTY)
            );
            return;
        }

        String paramId = request.getParameter(ID_PARAM);
        if (paramId == null || paramId.isBlank()) {
            sendResponse(
                    session,
                    new Response(Response.BAD_REQUEST, Response.EMPTY)
            );
            return;
        }

        try {
            workersPool.execute(() -> {
                try {
                    sendResponse(
                            session,
                            handleRequestToEntity(request, paramId)
                    );
                } catch (Exception e) {
                    sendResponse(
                            session,
                            new Response(Response.INTERNAL_ERROR, Response.EMPTY)
                    );
                }
            });
        } catch (RejectedExecutionException e) {
            sendResponse(
                    session,
                    new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY)
            );
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        workersPool.close();
    }

    private Response handleRequestToEntity(Request request, String id) {
        return switch (request.getMethod()) {
            case Request.METHOD_GET -> getEntity(id);
            case Request.METHOD_PUT -> putEntity(request, id);
            case Request.METHOD_DELETE -> deleteEntity(id);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }

    private Response getEntity(String id) {
        MemorySegment key = convertFromString(id);
        Entry<MemorySegment> entry = dao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response putEntity(Request request, String id) {
        MemorySegment key = convertFromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response deleteEntity(String id) {
        MemorySegment key = convertFromString(id);
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static MemorySegment convertFromString(String value) {
        return MemorySegment.ofArray(Utf8.toBytes(value));
    }

    private static HttpServerConfig configureServer(ServiceConfig serviceConfig) {
        var httpServerConfig = new HttpServerConfig();
        var acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    private static ExecutorService configureWorkersPool() {
        return new ThreadPoolExecutor(
                ExecutorServiceConfig.CORE_POOL_SIZE,
                ExecutorServiceConfig.MAX_CORE_POOL_SIZE,
                ExecutorServiceConfig.KEEP_ALIVE_TIME,
                TimeUnit.MILLISECONDS,
                ExecutorServiceConfig.queue,
                ExecutorServiceConfig.HANDLER
        );
    }

    private void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
