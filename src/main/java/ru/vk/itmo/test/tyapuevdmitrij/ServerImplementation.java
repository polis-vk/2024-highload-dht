package ru.vk.itmo.test.tyapuevdmitrij;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServerImplementation extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(ServerImplementation.class);

    private static final String ENTITY_PATH = "/v0/entity";

    private static final String REQUEST_KEY = "id=";

    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private static final int POOL_KEEP_ALIVE_SECONDS = 10;

    private static final int THREAD_POOL_QUEUE_SIZE = 64;

    private final MemorySegmentDao memorySegmentDao;

    private final ExecutorService executor;

    public ServerImplementation(ServiceConfig config, MemorySegmentDao memorySegmentDao) throws IOException {
        super(createServerConfig(config));
        this.memorySegmentDao = memorySegmentDao;
        this.executor = new ThreadPoolExecutor(THREAD_POOL_SIZE,
                THREAD_POOL_SIZE,
                POOL_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(THREAD_POOL_QUEUE_SIZE),
                new CustomThreadFactory("worker", true),
                new ThreadPoolExecutor.AbortPolicy());
        ((ThreadPoolExecutor) executor).prestartAllCoreThreads();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executor.close();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executor.execute(() -> {
                try {
                    if (!request.getPath().equals(ENTITY_PATH)) {
                        handleDefault(request, session);
                        return;
                    }
                    int requestMethod = request.getMethod();
                    String id = request.getParameter(REQUEST_KEY);
                    switch (requestMethod) {
                        case Request.METHOD_GET -> session.sendResponse(get(id));
                        case Request.METHOD_PUT -> session.sendResponse(put(id, request));
                        case Request.METHOD_DELETE -> session.sendResponse(delete(id));
                        default -> session.sendResponse(getUnsupportedMethodResponse());
                    }
                } catch (Exception e) {
                    logger.error("Exception in request method", e);
                    sendErrorResponse(session, Response.INTERNAL_ERROR);
                }
            });
        } catch (RejectedExecutionException exception) {
            logger.warn("ThreadPool queue overflow", exception);
            sendErrorResponse(session, Response.SERVICE_UNAVAILABLE);
        }

    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    private static void sendErrorResponse(HttpSession session, String error) {
        try {
            session.sendResponse(new Response(error, Response.EMPTY));
        } catch (IOException ex) {
            logger.error("can't send response", ex);
            session.close();
        }
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

    private Response get(String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        Entry<MemorySegment> entry = memorySegmentDao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response put(String id, Request request) {
        if (id == null || id.isEmpty() || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        byte[] requestBody = request.getBody();
        if (requestBody == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(requestBody));
        memorySegmentDao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response delete(String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)), null);
        memorySegmentDao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Response getUnsupportedMethodResponse() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }
}

