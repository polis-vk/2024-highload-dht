package ru.vk.itmo.test.tyapuevdmitrij;

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
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServerImplementation extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(ServerImplementation.class);

    private static final String ENTITY_PATH = "/v0/entity";

    private static final int threadPoolSize = 4;

    private static final int poolKeepAliveSeconds = 10;

    private static final int threadPoolQueueSize = 16;

    private final MemorySegmentDao memorySegmentDao;

    private final ExecutorService executor;

    public ServerImplementation(ServiceConfig config, MemorySegmentDao memorySegmentDao) throws IOException {
        super(createServerConfig(config));
        this.memorySegmentDao = memorySegmentDao;
        executor = new ThreadPoolExecutor(threadPoolSize, threadPoolSize, poolKeepAliveSeconds, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(threadPoolQueueSize));
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

    @Override
    public synchronized void stop() {
        super.stop();
        executor.close();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        executor.execute(() -> {
            try {
                if (!request.getPath().equals(ENTITY_PATH)) {
                    handleDefault(request, session);
                }
                int requestMethod = request.getMethod();
                switch (requestMethod) {
                    case Request.METHOD_GET -> session.sendResponse(get(request.getParameter("id=")));
                    case Request.METHOD_PUT -> session.sendResponse(put(request.getParameter("id="), request));
                    case Request.METHOD_DELETE -> session.sendResponse(delete(request.getParameter("id=")));
                    default -> session.sendResponse(unsupportedMethods());
                }
            } catch (Exception e) {
                logger.error("Exception in request method :", e);
                try {
                    session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                } catch (IOException ex) {
                    logger.error("can't send response", e);
                    session.close();
                }
            }

        });
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

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    private Response unsupportedMethods() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

}


