package ru.vk.itmo.test.nikitaprokopev;

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
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyServer extends HttpServer {
    private static final String BASE_PATH = "/v0/entity";
    private static final long MAX_RESPONSE_TIME = TimeUnit.SECONDS.toMillis(1);
    private final Logger log = LoggerFactory.getLogger(MyServer.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    private final ThreadPoolExecutor workerPool;

    public MyServer(
            ServiceConfig serviceConfig,
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ThreadPoolExecutor workerPool
    ) throws IOException {
        super(createServerConfig(serviceConfig));
        this.dao = dao;
        this.workerPool = workerPool;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.selectors = 4;
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.threads = 4;
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id, Request request) {
        MemorySegment msKey = (id == null || id.isEmpty()) ? null : toMemorySegment(id);
        if (msKey == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                MemorySegment.ofArray(request.getBody())
        );

        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) {
        MemorySegment msKey = (id == null || id.isEmpty()) ? null : toMemorySegment(id);
        if (msKey == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(msKey);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(toByteArray(entry.value()));
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        MemorySegment msKey = (id == null || id.isEmpty()) ? null : toMemorySegment(id);
        if (msKey == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                null
        );

        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    // For other methods
    @Path(BASE_PATH)
    public Response other() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    // For other requests - 400 Bad Request

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        long createdAt = System.currentTimeMillis();
        try {
            workerPool.execute(
                    () -> {
                        if (System.currentTimeMillis() - createdAt > MAX_RESPONSE_TIME) {
                            try {
                                session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
                                return;
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
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
            log.error("RejectedExecutionException", e);
            session.sendResponse(new Response(CustomResponseCodes.TOO_MANY_REQUESTS.getCode(), Response.EMPTY));
        } catch (Exception e) {
            handleRequestException(session, e);
        }
    }

    private void handleRequestException(HttpSession session, Exception e) {
        log.error("Error while handling request", e);
        try {
            if (e instanceof HttpException) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private MemorySegment toMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toByteArray(MemorySegment ms) {
        return ms.toArray(ValueLayout.JAVA_BYTE);
    }
}
