package ru.vk.itmo.test.dariasupriadkina;

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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

public class Server extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(Server.class.getName());
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private final Set<Integer> permittedMethods =
            Set.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE);

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao,
                  ThreadPoolExecutor executorService)
            throws IOException {
        super(createHttpServerConfig(config));
        this.dao = dao;
        this.executorService = executorService;
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();

        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        httpServerConfig.closeSessions = true;

        return httpServerConfig;
    }

    @Path("/health")
    @RequestMethod(Request.METHOD_GET)
    public Response health() {
        return Response.ok(Response.OK);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getHandler(@Param(value = "id", required = true) String id) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            Entry<MemorySegment> entry = getEntryById(id);
            if (entry == null || entry.value() == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }
            return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putHandler(Request request, @Param(value = "id", required = true) String id) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            dao.upsert(convertToEntry(id, request.getBody()));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteHandler(@Param(value = "id", required = true) String id) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            dao.upsert(convertToEntry(id, null));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (permittedMethods.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);

        }
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    logger.error("Unexpected error", e);
                    try {
                        if (e.getClass() == HttpException.class) {
                            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                        }
                    } catch (IOException ex) {
                        logger.error("Failed to send error response", e);
                        session.close();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("Service is unavailable", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));

        }
    }

    private Entry<MemorySegment> convertToEntry(String id, byte[] body) {
        return new BaseEntry<>(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                convertByteArrToMemorySegment(body));
    }

    private MemorySegment convertByteArrToMemorySegment(byte[] bytes) {
        return bytes == null ? null : MemorySegment.ofArray(bytes);
    }

    private Entry<MemorySegment> getEntryById(String id) {
        return dao.get(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)));
    }
}
