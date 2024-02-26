package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DaoHttpServer extends HttpServer {
    private static final String REQUEST_PATH = "/v0/entity";
    private static final byte[] INVALID_KEY_MESSAGE = "invalid id".getBytes(StandardCharsets.UTF_8);
    private static final Logger logger = LoggerFactory.getLogger(DaoHttpServer.class);
    private final ExecutorService workerPool;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public DaoHttpServer(
            final DaoHttpServerConfig config,
            final Dao<MemorySegment, Entry<MemorySegment>> dao
    ) throws IOException {
        super(config);
        this.dao = dao;
        if (config.useWorkers && config.useVirtualThreads) {
            this.workerPool = Executors.newVirtualThreadPerTaskExecutor();
        } else if (config.useWorkers) {
            final BlockingQueue<Runnable> blockingQueue;
            if (config.workerQueueType == WorkerQueueType.SYNCHRONOUS_QUEUE) {
                blockingQueue = new SynchronousQueue<>();
            } else {
                blockingQueue = new ArrayBlockingQueue<>(config.queueSize);
            }
            this.workerPool = new DaoWorkerPool(
                    config.minWorkers,
                    config.maxWorkers,
                    config.keepAliveTime,
                    TimeUnit.SECONDS,
                    blockingQueue
            );
        } else {
            this.workerPool = null;
        }
    }

    public Response get(final MemorySegment key) {
        final Entry<MemorySegment> entry = dao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response put(
            final MemorySegment key,
            final Request request
    ) {
        final MemorySegment value = MemorySegment.ofArray(request.getBody());
        final Entry<MemorySegment> entry = new BaseEntry<>(key, value);
        dao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response delete(final MemorySegment key) {
        final Entry<MemorySegment> entry = new BaseEntry<>(key, null);
        dao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(final Request request, final HttpSession session) {
        if (useWorkers) {
            workerPool.execute(() -> handleRequestTask(request, session));
        } else {
            handleRequestTask(request, session);
        }
    }

    private void handleRequestTask(final Request request, final HttpSession session) {
        try {
            final String uri = request.getURI();
            final String id = request.getParameter("id=");
            final Response validationFailedResponse = validate(uri, id);
            if (validationFailedResponse != null) {
                session.sendResponse(validationFailedResponse);
                return;
            }
            final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
            final int method = request.getMethod();
            Response response = getResponse(request, method, key);
            session.sendResponse(response);
        } catch (final IOException e) {
            logger.error("IOException in send response.");
        }
    }

    private Response getResponse(final Request request, final int method, final MemorySegment key) {
        Response response;
        try {
            response = switch (method) {
                case METHOD_GET -> get(key);
                case METHOD_DELETE -> delete(key);
                case METHOD_PUT -> put(key, request);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (final Exception e) {
            logger.error("Exception while handling request", e);
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return response;
    }

    public Response validate(
            final String path,
            final String id
    ) {
        if (!path.startsWith(REQUEST_PATH)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else if (isInvalidKey(id)) {
            return new Response(Response.BAD_REQUEST, INVALID_KEY_MESSAGE);
        }
        return null;
    }

    public boolean isInvalidKey(final String key) {
        return key == null || key.isEmpty();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        if (workerPool != null) {
            gracefulShutdown(workerPool);
        }
        try {
            dao.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void gracefulShutdown(final ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (executorService.awaitTermination(20, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executorService.shutdownNow();
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
