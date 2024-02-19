package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;


public class Server extends HttpServer {

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(configFromServiceConfig(config));
        this.dao = dao;
    }

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    private static HttpServerConfig configFromServiceConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = serviceConfig.selfPort();


        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)

    public Response getOne(@Param(value = "id", required = true) String id) {
        if (id.length() == 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        var key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        var val = dao.get(key);
        if (val == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(val.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putOne(@Param(value = "id", required = true) String id, Request request) {
        if (id.length() == 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        var key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        var val = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, val));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteOne(@Param(value = "id", required = true) String id) {
        if (id.length() == 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        var key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path("/v0/entity")
    public Response notAllowed() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }


    //     for debug only
    @Path("/v0/entity/all")
    @RequestMethod(Request.METHOD_GET)
    public Response listAll() {
        ByteArrayBuilder responseBuilder = new ByteArrayBuilder();
        for (Iterator<Entry<MemorySegment>> it = dao.get(null, null); it.hasNext(); ) {
            var entry = it.next();
            responseBuilder.append(entry.key().toArray(ValueLayout.JAVA_BYTE));
            responseBuilder.append(entry.value().toArray(ValueLayout.JAVA_BYTE));
        }
        return Response.ok(responseBuilder.toBytes());
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }
}
