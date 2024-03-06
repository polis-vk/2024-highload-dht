package ru.vk.itmo.test.georgiidalbeev;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class NewServer extends HttpServer {

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private static final String PATH = "/v0/entity";
    private static final long MAX_RESPONSE_TIME = TimeUnit.SECONDS.toNanos(1);
    private final Logger log = LoggerFactory.getLogger(NewServer.class);

    public NewServer(ServiceConfig config,
                     Dao<MemorySegment, Entry<MemorySegment>> dao,
                     ExecutorService executorService
    ) throws IOException {
        super(configureServer(config));
        this.dao = dao;
        this.executorService = executorService;
    }

    private static HttpServerConfig configureServer(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.selectors = 4;
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.threads = 4;
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        MemorySegment key = validateId(id);
        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        try {
            Entry<MemorySegment> entry = new BaseEntry<>(
                    key,
                    MemorySegment.ofArray(request.getBody())
            );

            dao.upsert(entry);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id", required = true) String id) {
        MemorySegment key = validateId(id);
        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        try {
            Entry<MemorySegment> entry = dao.get(key);

            if (entry == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }

            return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        MemorySegment key = validateId(id);
        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        try {
            Entry<MemorySegment> entry = new BaseEntry<>(
                    key,
                    null
            );

            dao.upsert(entry);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(PATH)
    public Response otherMethods() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    private MemorySegment validateId(String id) {
        return (id == null || id.isEmpty()) ? null : MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        long createdAt = System.nanoTime();
        try {
            executorService.execute(
                    () -> {
                        if (System.nanoTime() - createdAt > MAX_RESPONSE_TIME) {
                            try {
                                session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
                                return;
                            } catch (IOException e) {
                                log.error("Exception while sending close connection", e);
                                session.scheduleClose();
                                return;
                            }
                        }

                        try {
                            super.handleRequest(request, session);
                        } catch (Exception e) {
                            handleRequestException(session, e);
                        }
                    }
            );
        } catch (RejectedExecutionException e) {
            log.error("Pool queue overflow", e);
            session.sendResponse(new Response(NewResponseCodes.TOO_MANY_REQUESTS.getCode(), Response.EMPTY));
        } catch (Exception e) {
            handleRequestException(session, e);
        }
    }

    private void handleRequestException(HttpSession session, Exception e) {
        try {
            if (e instanceof HttpException) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            log.error("Exception while sending close connection", e);
            session.scheduleClose();
        }
    }
}
