package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DaoHttpServer extends HttpServer {
    private static final String REQUEST_PATH = "/v0/entity";
    private static final String SERVER_STOP_PATH = "/stop";
    private static final byte[] INVALID_KEY_MESSAGE = "invalid id".getBytes(StandardCharsets.UTF_8);
    private static final Logger logger = LoggerFactory.getLogger(DaoHttpServer.class);
    private final ExecutorService workerPool;
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
        this.redirectService = new RedirectService(config.selfUrl, config.clusterUrls);
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
        final TimeEntry<MemorySegment> entry = new TimeEntry<>(epochMillisNow(), key, value);
        dao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response delete(final MemorySegment key) {
        final TimeEntry<MemorySegment> entry = new TimeEntry<>(epochMillisNow(), key, null);
        dao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
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
            }

            final String id = request.getParameter("id=");
            final Response validationFailedResponse = validateResponse(path, id);
            if (validationFailedResponse != null) {
                session.sendResponse(validationFailedResponse);
                return;
            }
            final String redirectHeader = request.getHeader(RedirectService.REDIRECT_HEADER_NAME);
            logger.info(selfUrl);
            for (final String header: request.getHeaders()) {
                logger.info(header);
            }
            if (redirectHeader != null && redirectHeader.equals("true")) {
                final int method = request.getMethod();
                final byte[] keyBytes = id.getBytes(StandardCharsets.UTF_8);
                final MemorySegment key = MemorySegment.ofArray(keyBytes);
                session.sendResponse(processRequest(request, method, key));
                return;
            }
            final String ackParam = request.getParameter("ack=");
            final String fromParam = request.getParameter("from=");
            final int from;
            if (fromParam == null) {
                from = balancer.clusterSize();
            } else {
                try {
                    from = Integer.parseInt(fromParam);
                } catch (final NumberFormatException e) {
                    session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                    return;
                }
            }
            final int ack;
            if (ackParam == null) {
                ack = balancer.quorum(from);
            } else {
                try {
                    ack = Integer.parseInt(ackParam);
                } catch (final NumberFormatException e) {
                    session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                    return;
                }
            }
            if (ack < 1 || from < 1 || ack > from) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            final String[] nodeUrls = balancer.getNodeUrls(id, from);
            int success = 0;
            Response response = null;
            for (final String url : nodeUrls) {
                final Response curResponse;
                if (url.equals(selfUrl)) {
                    final int method = request.getMethod();
                    final byte[] keyBytes = id.getBytes(StandardCharsets.UTF_8);
                    final MemorySegment key = MemorySegment.ofArray(keyBytes);
                    curResponse = processRequest(request, method, key);
                } else {
                    curResponse = shardResponse(request, url);
                }
                final int status = curResponse.getStatus();
                if (200 <= status && status < 300) {
                    success++;
                    response = curResponse;
                }
            }
            if (success >= ack) {
                session.sendResponse(response);
            } else {
                session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
            }
        } catch (final IOException e) {
            logger.error("IOException in send response.");
        }
    }

    private Response shardResponse(
            final Request request,
            final String nodeUrl
    ) {
        try {
            return redirectService.redirect(nodeUrl, request);
        } catch (final IOException e) {
            logger.error("IOException in get response from node {}", nodeUrl, e);
        } catch (final HttpException e) {
            logger.error("HttpException in sending to node {}", nodeUrl, e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Timeout in sending to node {}", nodeUrl, e);
        }
        return new Response(Response.BAD_GATEWAY, Response.EMPTY);
    }

    private Response processRequest(final Request request, final int method, final MemorySegment key) {
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

    public Response validateResponse(
            final String path,
            final String id
    ) {
        if (!path.equals(REQUEST_PATH)) {
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
