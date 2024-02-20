package ru.vk.itmo.test.kachmareugene;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class HttpServerImpl extends HttpServer {

    private static final Response NOT_FOUND =
             new Response(Response.NOT_FOUND, Response.EMPTY);
    private static final Response ACCEPTED = new Response(Response.ACCEPTED, Response.EMPTY);
    Dao<MemorySegment, Entry<MemorySegment>> daoImpl;
    private final ServiceConfig serviceConfig;
    private final Response CREATED = new Response(Response.CREATED, Response.EMPTY);
    private final Response BAD_REQUEST = new Response(Response.BAD_REQUEST, Response.EMPTY);

    public HttpServerImpl(ServiceConfig conf) throws IOException {
        super(convertToHttpConfig(conf));
        this.serviceConfig = conf;
    }

    private static HttpServerConfig convertToHttpConfig(ServiceConfig conf) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = conf.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.closeSessions = true;
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        return httpServerConfig;
    }


    // :TODO: MAGICAL CONST!!!
    @Override
    public synchronized void start() {
        try {
            daoImpl = new ReferenceDao(new Config(serviceConfig.workingDir(), 16384));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        super.start();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            daoImpl.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getEntry(
            @Param("id") String key) {

        if (key == null || key.isEmpty()) {
            return BAD_REQUEST;
        }

        Entry<MemorySegment> result = daoImpl.get(MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)));

        if (result == null) {
            return NOT_FOUND;
            //return Response.ok(Response.OK);
        }
        return new Response("200", result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putOrEmplaceEntry(
            @Param("id") String key,
            Request request) {

        if (key == null || key.isEmpty()) {
            return BAD_REQUEST;
        }

        daoImpl.upsert(
                new BaseEntry<>(MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                                MemorySegment.ofArray(request.getBody())));
        return CREATED;
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param("id") String key) {
        if (key.isEmpty()) {
            return BAD_REQUEST;
        }
        daoImpl.upsert(new BaseEntry<>(MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                        null));
        return ACCEPTED;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            super.handleRequest(request, session);
        } catch (RuntimeException e) {
            session.sendError(Response.BAD_REQUEST, e.toString());
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        int method = request.getMethod();
        Response response;

        if (method != Request.METHOD_PUT
                && method != Request.METHOD_DELETE
                && method != Request.METHOD_GET) {

            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        } else {
            response = BAD_REQUEST;
        }
        session.sendResponse(response);
    }
}
