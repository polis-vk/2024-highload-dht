package ru.vk.itmo.test.reshetnikovaleksei;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class HttpServerImpl extends HttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private static final String ID_PARAMETER_NAME = "id=";

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
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String id = request.getParameter(ID_PARAMETER_NAME);
        if (id == null || id.isBlank()) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        try {
            executorService.execute(() -> {
                try {
                    String targetNode = requestRouter.getNode(id);
                    if (Objects.equals(targetNode, selfUrl)) {
                        super.handleRequest(request, session);
                    } else {
                        Response response = requestRouter.redirect(targetNode, request);
                        session.sendResponse(response);
                    }
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
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }

            Entry<MemorySegment> result = dao.get(parseToMemorySegment(id));
            if (result == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }

            return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id, Request request) {
        try {
            if (id.isEmpty() || request.getBody() == null) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }

            dao.upsert(new BaseEntry<>(parseToMemorySegment(id), MemorySegment.ofArray(request.getBody())));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }

            dao.upsert(new BaseEntry<>(parseToMemorySegment(id), null));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
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
    public synchronized void stop() {
        if (isStopped.getAndSet(true)) {
            return;
        }

        super.stop();
    }

    private MemorySegment parseToMemorySegment(String input) {
        return MemorySegment.ofArray(input.getBytes(StandardCharsets.UTF_8));
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
