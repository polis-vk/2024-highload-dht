package ru.vk.itmo.test.bazhenovkirill;

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
import ru.vk.itmo.test.bazhenovkirill.dao.DaoImpl;

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

public class ServerImpl extends HttpServer {

    private static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;

    private static final String API_ENDPOINT = "/v0/entity";

    private static final Logger logger = LoggerFactory.getLogger(ServerImpl.class);

    private static final int CORE_POOL_SIZE = 3;

    private static final int MAX_POOL_SIZE = 5;

    private static final int KEEP_ALIVE_TIME = 15;

    private static final int BLOCKING_QUEUE_SIZE = 50;

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    private final ExecutorService executorService;

    public ServerImpl(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        executorService = createThreadPoolExecutor();
        dao = new DaoImpl(new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES));
    }

    @Path(API_ENDPOINT)
    public void handleEntityRequest(Request request, @Param(value = "id") String id, HttpSession session) {
        try {
            executorService.execute(() -> {
                Response response = processRequest(request, id);
                try {
                    session.sendResponse(response);
                } catch (IOException ex) {
                    logger.error("caught IOException within - ", ex);
                    throw new UncheckedIOException(ex);
                }
            });
        } catch (RejectedExecutionException rex) {
            logger.error("caught exception in executor service - ", rex);
            try {
                session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    private Response processRequest(Request request, String id) {
        if (isInvalidId(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                return getEntityById(id);
            }
            case Request.METHOD_DELETE -> {
                return deleteEntityById(id);
            }
            case Request.METHOD_PUT -> {
                return putEntity(id, request);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private Response deleteEntityById(String id) {
        MemorySegment key = convertStringToMemorySegment(id);
        try {
            dao.upsert(new BaseEntry<>(key, null));
        } catch (IllegalStateException ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Response getEntityById(String id) {
        MemorySegment key = convertStringToMemorySegment(id);
        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response putEntity(String id, Request request) {
        MemorySegment key = convertStringToMemorySegment(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        try {
            dao.upsert(new BaseEntry<>(key, value));
        } catch (IllegalStateException ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            super.handleRequest(request, session);
        } catch (IOException ex) {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            shutdownExecutor();
            dao.close();
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private void shutdownExecutor() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadPoolExecutor createThreadPoolExecutor() {
        return new ThreadPoolExecutor(CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BLOCKING_QUEUE_SIZE));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private boolean isInvalidId(String id) {
        return id == null || id.isBlank();
    }

    private MemorySegment convertStringToMemorySegment(String id) {
        return MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }
}
