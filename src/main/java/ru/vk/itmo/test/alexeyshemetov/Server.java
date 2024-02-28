package ru.vk.itmo.test.alexeyshemetov;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.alexeyshemetov.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class Server extends HttpServer {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String ENTITY_PATH = "/v0/entity";
    private static final long MEM_DAO_SIZE = 1L << 16;
    private static final int KEEP_ALIVE_THREAD = 30;
    private static final TimeUnit KEEP_ALIVE_THREAD_UNIT = TimeUnit.SECONDS;
    private static final int AWAIT_TERMINATION_TIME = 5;
    private static final TimeUnit AWAIT_TERMINATION_UNIT = TimeUnit.MINUTES;
    public static final int QUEUE_CAPACITY = 1024;
    private final ExecutorService executorService;

    public Server(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        try {
            dao = new ReferenceDao(new Config(config.workingDir(), MEM_DAO_SIZE));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't start server", e);
        }
        executorService = getExecutorService();
    }

    @Path(ENTITY_PATH)
    public Response entityById(
        @Param(value = "id", required = true) final String id,
        Request request
    ) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        return switch (request.getMethod()) {
            case Request.METHOD_GET -> getById(id);
            case Request.METHOD_PUT -> putById(id, request);
            case Request.METHOD_DELETE -> deleteById(id);
            default -> notAllowed();
        };
    }

    public Response getById(final String id) {
        MemorySegment key = toMemorySegment(id);
        Entry<MemorySegment> segmentEntry = dao.get(key);
        if (segmentEntry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(segmentEntry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response putById(final String id, Request request) {
        MemorySegment key = toMemorySegment(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response deleteById(final String id) {
        MemorySegment key = toMemorySegment(id);
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    public Response notAllowed() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            Future<?> submit = executorService.submit(() -> {
                try {
                    handleRequestWrapper(request, session);
                } catch (IOException e) {
                    throw new UncheckedIOException("Error while trying wrap request: " + request, e);
                }
            });
        } catch (RejectedExecutionException e) {
            Response response = new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
            session.sendResponse(response);
        }
    }

    private void handleRequestWrapper(Request request, HttpSession session) throws IOException {
        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            handleDefault(request, session);
        }
    }

    @Override
    public synchronized void stop() {
        shutdownAwait(executorService);
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Error while stopping server", e);
        }
    }

    private static void shutdownAwait(ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(AWAIT_TERMINATION_TIME, AWAIT_TERMINATION_UNIT)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService getExecutorService() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores / 2,
            cores,
            KEEP_ALIVE_THREAD,
            KEEP_ALIVE_THREAD_UNIT,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    private static MemorySegment toMemorySegment(String string) {
        return MemorySegment.ofArray(string.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }
}
