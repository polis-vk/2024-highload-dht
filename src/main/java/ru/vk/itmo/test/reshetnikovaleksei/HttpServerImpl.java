package ru.vk.itmo.test.reshetnikovaleksei;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
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
import java.net.http.HttpTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class HttpServerImpl extends HttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private final RequestRouter requestRouter;
    private final String selfUrl;
    private final AtomicBoolean isStopped;

    public HttpServerImpl(ServiceConfig config,
                          Dao<MemorySegment, Entry<MemorySegment>> dao,
                          ExecutorService executorService,
                          RequestRouter requestRouter) throws IOException {
        super(createConfig(config));
        this.dao = dao;
        this.executorService = executorService;
        this.requestRouter = requestRouter;
        this.selfUrl = config.selfUrl();
        this.isStopped = new AtomicBoolean(false);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        if (request.getMethod() == Request.METHOD_GET) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        } else {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (IOException e) {
                    processIOException(request, session, e);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle request: {} with error: {}", request, e.getMessage());
                    try {
                        if (e.getClass() == HttpException.class) {
                            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                        }
                    } catch (IOException ex) {
                        processIOException(request, session, ex);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.error("Failed to execute task for request: {} with error: {}", request, e.getMessage());
            try {
                session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
            } catch (IOException ex) {
                processIOException(request, session, ex);
            }
        }
    }

    @Path("/v0/entity")
    public Response entity(Request request, @Param(value = "id") String id) {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String executorNode = requestRouter.getNodeByEntityId(id);
        if (executorNode.equals(selfUrl)) {
            return invokeLocal(request, id);
        }

        try {
            return requestRouter.redirect(executorNode, request);
        } catch (HttpTimeoutException e) {
            LOGGER.error("timeout while invoking remote node");
            return new Response(Response.REQUEST_TIMEOUT, Response.EMPTY);
        } catch (IOException e) {
            LOGGER.error("I/O exception while calling remote node");
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Thread interrupted");
            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
        }
    }

    @Override
    public synchronized void stop() {
        if (isStopped.getAndSet(true)) {
            return;
        }

        super.stop();
    }

    private Response invokeLocal(Request request, String id) {
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

    private void processIOException(Request request, HttpSession session, IOException e) {
        LOGGER.error("Failed to send response for request: {} with error: {}", request, e.getMessage());
        session.close();
    }

    private static HttpServerConfig createConfig(ServiceConfig config) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }
}
