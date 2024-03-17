package ru.vk.itmo.test.elenakhodosova;

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
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String PATH_NAME = "/v0/entity";
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private final ExecutorService executorService;
    private static final Logger logger = LoggerFactory.getLogger(HttpServerImpl.class);

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao, ExecutorService executorService) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
        this.executorService = executorService;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> processRequest(request, session));
        } catch (RejectedExecutionException e) {
            logger.error("Request rejected", e);
            session.sendResponse(new Response(TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private void processRequest(Request request, HttpSession session) {
        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            logger.error("Unexpected error when processing request", e);
            sendError(session, e);
        }
    }

    private void sendError(HttpSession session, Exception e) {
        try {
            String responseCode = e.getClass() == HttpException.class ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
            logger.error("Send error", e);
            session.sendError(responseCode, null);
        } catch (Exception ex) {
            logger.error("Unexpected error when sending error", ex);
        }
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id", required = true) String id) {
        if (isParamIncorrect(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        try {
            Entry<MemorySegment> value = dao.get(MemorySegment.ofArray(Utf8.toBytes(id)));
            return value == null ? new Response(Response.NOT_FOUND, Response.EMPTY)
                    : Response.ok(value.value().toArray(ValueLayout.JAVA_BYTE));
        } catch (Exception ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        if (isParamIncorrect(id) || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        byte[] value = request.getBody();
        try {
            dao.upsert(new BaseEntry<>(
                    MemorySegment.ofArray(Utf8.toBytes(id)),
                    MemorySegment.ofArray(value)));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (Exception ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        if (isParamIncorrect(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        try {
            dao.upsert(new BaseEntry<>(MemorySegment.ofArray(Utf8.toBytes(id)), null));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (Exception ex) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(PATH_NAME)
    public Response methodNotSupported() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response badRequest = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(badRequest);
    }

    private boolean isParamIncorrect(String param) {
        return param == null || param.isEmpty();
    }
}
