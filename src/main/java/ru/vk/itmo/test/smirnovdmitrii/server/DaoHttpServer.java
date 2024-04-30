package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;
import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DaoHttpServer extends HttpServer {

    @DhtValue("server.use.async:true")
    private static boolean useAsync;
    @DhtValue("server.redirect.pool.size:30")
    private static int REDIRECT_POOL_SIZE;

    private static final String REQUEST_PATH = "/v0/entity";
    private static final String SERVER_STOP_PATH = "/stop";
    private static final byte[] INVALID_KEY_MESSAGE = "invalid id".getBytes(StandardCharsets.UTF_8);
    private static final Logger logger = LoggerFactory.getLogger(DaoHttpServer.class);
    private final ExecutorService workerPool;
    private final ExecutorService redirectPool;
    private final Dao<MemorySegment, TimeEntry<MemorySegment>> dao;
    private final Balancer balancer;
    private final RedirectService redirectService;
    private final String selfUrl;
    private boolean stopped = false;

    public DaoHttpServer(
            final DaoHttpServerConfig config,
            final Dao<MemorySegment, TimeEntry<MemorySegment>> dao
    ) throws IOException {
        super(config);
        this.dao = dao;
        this.balancer = new Balancer(config.clusterUrls);
        this.redirectPool = Executors.newFixedThreadPool(REDIRECT_POOL_SIZE);
        this.redirectService = new RedirectService(redirectPool);
        this.selfUrl = config.selfUrl;
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
        this.useWorkers = config.useWorkers;
    }

    public ProcessResult get(final MemorySegment key) {
        final TimeEntry<MemorySegment> entry = dao.get(key);
        if (entry == null) {
            return new ProcessResult(HTTP_NOT_FOUND, Response.EMPTY);
        } else if (entry.value() == null) {
            return new ProcessResult(HTTP_NOT_FOUND, Response.EMPTY, epochMillisNow());
        }
        return new ProcessResult(
                HTTP_OK,
                entry.value().toArray(ValueLayout.JAVA_BYTE),
                epochMillisNow()
        );
    }

    public ProcessResult put(
            final MemorySegment key,
            final Request request
    ) {
        final MemorySegment value = MemorySegment.ofArray(request.getBody());
        final TimeEntry<MemorySegment> entry = new TimeEntry<>(epochMillisNow(), key, value);
        dao.upsert(entry);
        return new ProcessResult(HTTP_CREATED, Response.EMPTY);
    }

    public ProcessResult delete(final MemorySegment key) {
        final TimeEntry<MemorySegment> entry = new TimeEntry<>(epochMillisNow(), key, null);
        dao.upsert(entry);
        return new ProcessResult(HTTP_ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(final Request request, final HttpSession session) {
        if (useWorkers) {
            try {
                workerPool.execute(() -> handleRequestTask(request, session));
            } catch (final RejectedExecutionException e) {
                logger.error("rejected request handle", e);
                try (session) {
                    session.sendError(Response.SERVICE_UNAVAILABLE, null);
                } catch (final IOException ignored) {
                    // do nothing
                }
            }
        } else {
            handleRequestTask(request, session);
        }
    }

    private void handleRequestTask(final Request request, final HttpSession session) {
        try {
            final String path = request.getPath();
            if (path.equals(SERVER_STOP_PATH)) {
                stop();
                return;
            } else if (path.startsWith("/v0/entities")) {
                processRange(request, session);
                return;
            }
            final String id = request.getParameter("id=");
            final ProcessResultHandler handler = processHandler(session, request, id, path);
            if (handler.isClosed()) {
                return;
            }
            final String redirectHeader = request.getHeader(Utils.REDIRECT_ONE_NIO_HEADER_NAME);
            if (redirectHeader != null && redirectHeader.equals("true")) {
                handler.sendResult(processRequest(request, handler.method(), id));
                return;
            }
            processRequestAck(request, id, handler);
        } catch (final IOException e) {
            logger.error("IOException in send response.");
        } catch (final InterruptedException e) {
            logger.error("Interrupted while processing tasks.");
            Thread.currentThread().interrupt();
        }
    }

    private void processRange(final Request request, final HttpSession session) throws IOException {
        final String startString = request.getParameter("start=");
        final String endString = request.getParameter("end=");
        if (startString == null || startString.isBlank() || (endString != null && endString.isBlank())) {
            session.sendError(Response.BAD_REQUEST, "Missing range parameter \"start\".");
            return;
        }
        final MemorySegment startKey = MemorySegment.ofArray(startString.getBytes(StandardCharsets.UTF_8));
        final MemorySegment endKey;
        if (endString == null) {
            endKey = null;
        } else {
            endKey = MemorySegment.ofArray(endString.getBytes(StandardCharsets.UTF_8));
        }

        final Iterator<TimeEntry<MemorySegment>> iterator = dao.get(startKey, endKey);
        if (!iterator.hasNext()) {
            session.sendResponse(new Response(Response.OK, Response.EMPTY));
            return;
        }
        ((DaoSession) session).range(iterator);
        session.close();
    }

    @Override
    public HttpSession createSession(final Socket socket) {
        return new DaoSession(socket, this);
    }

    private void processRequestAck(
            final Request request,
            final String id,
            final ProcessResultHandler handler
    ) throws IOException, InterruptedException {
        final String[] nodeUrls = balancer.getNodeUrls(id, handler.from());
        if (useAsync) {
            processRequestAckAsync(request, id, handler, nodeUrls);
        } else {
            processRequestAckSync(request, id, handler, nodeUrls);
        }
    }

    private void processRequestAckSync(
            final Request request,
            final String id,
            final ProcessResultHandler handler,
            final String[] nodeUrls
    ) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(nodeUrls.length);
        for (final String url: nodeUrls) {
            if (url.equals(selfUrl)) {
                redirectPool.execute(() -> {
                    try {
                        handler.add(processRequest(request, handler.method(), id));
                    } finally {
                        latch.countDown();
                    }
                });
            } else {
                redirectPool.execute(() -> {
                    try {
                        redirectService.redirectSync(url, request, handler);
                    } catch (final Exception e) {
                        handler.add(new ProcessResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, Response.EMPTY));
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
        latch.await();
        if (handler.isOutcomeSuccess()) {
            handler.sendResult(handler.outcome.get());
        } else {
            handler.sendNotEnoughReplicas();
        }
    }

    private void processRequestAckAsync(
            final Request request,
            final String id,
            final ProcessResultHandler handler,
            final String[] nodeUrls
    ) {
        boolean isLocal = false;
        for (final String url : nodeUrls) {
            if (url.equals(selfUrl)) {
                isLocal = true;
            } else {
                redirectService.redirectAsync(url, request, handler);
            }
        }
        if (isLocal) {
            handler.add(processRequest(request, handler.method(), id));
        }
    }

    private ProcessResult processRequest(final Request request, final int method, final String id) {
        final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        ProcessResult response;
        try {
            response = switch (method) {
                case METHOD_GET -> get(key);
                case METHOD_DELETE -> delete(key);
                case METHOD_PUT -> put(key, request);
                default -> new ProcessResult(HTTP_BAD_METHOD, Response.EMPTY, -1);
            };
        } catch (final Exception e) {
            logger.error("Exception while handling request", e);
            response = new ProcessResult(HTTP_INTERNAL_ERROR, Response.EMPTY, -1);
        }
        return response;
    }

    private ProcessResultHandler processHandler(
            final HttpSession session,
            final Request request,
            final String id,
            final String path
    ) {
        final int method = request.getMethod();
        final String ackParam = request.getParameter("ack=");
        final String fromParam = request.getParameter("from=");
        final int from;
        if (fromParam == null) {
            from = balancer.clusterSize();
        } else {
            try {
                from = Integer.parseInt(fromParam);
            } catch (final NumberFormatException e) {
                final ProcessResultHandler execHandler = new ProcessResultHandler(session, method, -1, -1);
                execHandler.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return execHandler;
            }
        }
        final int ack;
        if (ackParam == null) {
            ack = balancer.quorum(from);
        } else {
            try {
                ack = Integer.parseInt(ackParam);
            } catch (final NumberFormatException e) {
                final ProcessResultHandler execHandler = new ProcessResultHandler(session, method, -1, -1);
                execHandler.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return execHandler;
            }
        }
        final ProcessResultHandler handler = new ProcessResultHandler(session, method, ack, from);
        if (ack < 1 || from < 1 || ack > from) {
            handler.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        } else if (!path.equals(REQUEST_PATH)) {
            handler.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        } else if (isInvalidKey(id)) {
            handler.sendResponse(new Response(Response.BAD_REQUEST, INVALID_KEY_MESSAGE));
        } else if (METHOD_GET != method && METHOD_DELETE != method && METHOD_PUT != method) {
            handler.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
        return handler;
    }

    private boolean isInvalidKey(final String key) {
        return key == null || key.isEmpty();
    }

    @Override
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        logger.info("server start stop.");
        stopped = true;
        super.stop();
        redirectService.close();
        if (workerPool != null) {
            gracefulShutdown(workerPool);
        }
        if (redirectPool != null) {
            gracefulShutdown(redirectPool);
        }
        try {
            dao.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        logger.info("server stopped.");
    }

    private static void gracefulShutdown(final ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (executorService.awaitTermination(20, TimeUnit.SECONDS)) {
                return;
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        executorService.shutdownNow();
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long epochMillisNow() {
        return Instant.now().toEpochMilli();
    }
}
