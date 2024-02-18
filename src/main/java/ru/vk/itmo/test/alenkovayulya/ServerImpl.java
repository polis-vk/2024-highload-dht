package ru.vk.itmo.test.alenkovayulya;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.alenkovayulya.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class ServerImpl extends HttpServer {

    private final ReferenceDao referenceDao;

    public ServerImpl(ServiceConfig serviceConfig, ReferenceDao referenceDao) throws IOException {
        super(createServerConfig(serviceConfig));
        this.referenceDao = referenceDao;
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

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id", required = true) String id) {
        if (isEmptyId(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> value = referenceDao.get(convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)));

        return value == null ? new Response(Response.NOT_FOUND, Response.EMPTY)
                : Response.ok(value.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        if (isEmptyId(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        referenceDao.upsert(new BaseEntry<>(
                convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                convertBytesToMemorySegment(request.getBody())));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        if (isEmptyId(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        referenceDao.upsert(new BaseEntry<>(
                convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        switch (request.getMethodName()) {
            case "GET", "PUT", "DELETE" -> session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            default -> session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            referenceDao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean isEmptyId(String id) {
        return id.isEmpty() && id.isBlank();
    }

    private MemorySegment convertBytesToMemorySegment(byte[] byteArray) {
        return MemorySegment.ofArray(byteArray);
    }
}
