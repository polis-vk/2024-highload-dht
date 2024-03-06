package ru.vk.itmo.test.pelogeikomakar;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class DaoHttpServer extends one.nio.http.HttpServer {

    private static final Logger log = LoggerFactory.getLogger(DaoHttpServer.class);

    private final ExecutorService executorService;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final Set<Integer> ALLOWED_METHODS = Set.of(Request.METHOD_GET, Request.METHOD_PUT,
            Request.METHOD_DELETE);


    public DaoHttpServer(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao,
                         ExecutorService executorService) throws IOException {
        super(createHttpServerConfig(config));
        this.dao = dao;
        this.executorService = executorService;
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

    public Dao<MemorySegment, Entry<MemorySegment>> getDao() {
        return dao;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsertDaoMethod(@Param(value = "id", required = false) String id, Request request) {

        if (id == null || id.isEmpty() || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        try {
            dao.upsert(requestToEntry(id, request.getBody()));
        } catch (IllegalStateException e) {
            log.error("Exception during upsert (key: {})", id, e);
            return new Response(Response.CONFLICT, Response.EMPTY);
        }

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getDaoMethod(@Param(value = "id", required = false) String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> result = dao.get(stringToMemorySegment(id));

        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(memorySegmentToBytes(result.value()));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteDaoMethod(@Param(value = "id", required = false) String id) {

        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        try {
            dao.upsert(requestToEntry(id, null));
        } catch (IllegalStateException e) {
            log.error("Exception during delete-upsert", e);
            return new Response(Response.CONFLICT, Response.EMPTY);
        }

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    try {
                        session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                    } catch (IOException ex) {
                        log.error("IOException while sendResponse ExecServer", ex);
                        session.scheduleClose();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Exception during adding request to ExecServ queue", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {

        Response response;
        if (ALLOWED_METHODS.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    private MemorySegment stringToMemorySegment(String str) {
        return MemorySegment.ofArray(str.getBytes(StandardCharsets.UTF_8));
    }

    private Entry<MemorySegment> requestToEntry(String key, byte[] value) {
        return new BaseEntry<>(stringToMemorySegment(key), value == null ? null : MemorySegment.ofArray(value));
    }

    private byte[] memorySegmentToBytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }
}
