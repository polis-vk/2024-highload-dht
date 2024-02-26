package ru.vk.itmo.test.elenakhodosova;

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
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ExecutorService;

public class HttpServerImpl extends HttpServer {

    private final ReferenceDao dao;
    private static final String PATH_NAME = "/v0/entity";
    private final ExecutorService executorService;

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao, ExecutorService executorService) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
        this.executorService = executorService;
    }
    @Override
    public void handleRequest(Request request, HttpSession session) {
        executorService.execute(() -> {
            try {
                super.handleRequest(request, session);
            } catch (Exception e) {
                try {
                    session.sendError(Response.BAD_REQUEST, e.getMessage());
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        });
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
        Entry<MemorySegment> value = dao.get(MemorySegment.ofArray(Utf8.toBytes(id)));
        return value == null ? new Response(Response.NOT_FOUND, Response.EMPTY)
                : Response.ok(value.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        byte[] value = request.getBody();
        if (isParamIncorrect(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        dao.upsert(new BaseEntry<>(
                MemorySegment.ofArray(Utf8.toBytes(id)),
                MemorySegment.ofArray(value)));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(PATH_NAME)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        if (isParamIncorrect(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);
        dao.upsert(new BaseEntry<>(MemorySegment.ofArray(Utf8.toBytes(id)), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
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

    @Override
    public synchronized void stop() {
        executorService.shutdownNow();
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isParamIncorrect(String param) {
        return param == null || param.isEmpty();
    }
}
