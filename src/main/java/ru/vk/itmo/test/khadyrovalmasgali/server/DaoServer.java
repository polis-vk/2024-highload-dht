package ru.vk.itmo.test.khadyrovalmasgali.server;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.khadyrovalmasgali.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DaoServer extends HttpServer {

    private static final Logger log = LoggerFactory.getLogger(DaoServer.class);
    private static final String ENTITY_PATH = "/v0/entity";
    private static final int FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1MB
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final int Q_SIZE = 512;
    private static final int KEEP_ALIVE_TIME = 50;
    private static final int TIMEOUT = 60;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ServiceConfig config;
    private final ExecutorService executorService = new ThreadPoolExecutor(
            THREADS,
            THREADS,
            KEEP_ALIVE_TIME,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Q_SIZE),
            new ThreadPoolExecutor.AbortPolicy());

    public DaoServer(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        this.config = config;
    }

    @Override
    public synchronized void start() {
        super.start();
        try {
            this.dao = new ReferenceDao(new Config(
                    config.workingDir(),
                    FLUSH_THRESHOLD_BYTES));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    if (e instanceof HttpException) {
                        sendResponseSafe(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                    } else {
                        sendResponseSafe(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Can't handle request", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    @Path(ENTITY_PATH)
    public Response entity(@Param(value = "id", required = true) String id, Request request) {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> entry = dao.get(stringToMemorySegment(id));
                if (entry == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                }
                return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            }
            case Request.METHOD_PUT -> {
                dao.upsert(new BaseEntry<>(
                        stringToMemorySegment(id),
                        MemorySegment.ofArray(request.getBody())));
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                dao.upsert(new BaseEntry<>(stringToMemorySegment(id), null));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        shutdownAndAwaitTermination(executorService);
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(TIMEOUT, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(TIMEOUT, TimeUnit.SECONDS)) {
                    log.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void sendResponseSafe(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MemorySegment stringToMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }
}
