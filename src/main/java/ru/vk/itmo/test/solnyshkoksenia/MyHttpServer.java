package ru.vk.itmo.test.solnyshkoksenia;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.os.Mem;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.DaoImpl;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MyHttpServer extends HttpServer {
    public final DaoImpl dao;
    public MyHttpServer(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        this.dao = new DaoImpl(createConfig(config));
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static Config createConfig(ServiceConfig config) {
        return new Config(config.workingDir(), 64);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        if (request.getMethod() == Request.METHOD_GET) {
            session.sendResponse(new Response(Response.BAD_REQUEST));
        }
        session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(final HttpSession session,
                        @Param(value = "id", required = true) String id) throws IOException {
        Response response;
        if (id.isEmpty()) {
            response = new Response(Response.BAD_REQUEST);
        } else {
            Entry<MemorySegment> entry = dao.get(toMS(id));
            if (entry == null) {
                response = new Response(Response.NOT_FOUND);
            } else {
                response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            }
        }
        session.sendResponse(response);
        return response;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response put(final Request request, final HttpSession session,
                        @Param(value = "id", required = true) String id) throws IOException {
        Response response;
        if (id.isEmpty()) {
            response = new Response(Response.BAD_REQUEST);
        } else {
            dao.upsert(new BaseEntry<>(toMS(id), MemorySegment.ofArray(request.getBody())));
            response = new Response(Response.CREATED);
        }
        session.sendResponse(response);
        return response;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(final HttpSession session,
                           @Param(value = "id", required = true) String id) throws IOException {
        Response response;
        if (id.isEmpty()) {
            response = new Response(Response.BAD_REQUEST);
        } else {
            dao.upsert(new BaseEntry<>(toMS(id), null));
            response = new Response(Response.ACCEPTED);
        }
        session.sendResponse(response);
        return response;
    }

    private MemorySegment toMS(String input) {
        return MemorySegment.ofArray(input.getBytes(UTF_8));
    }
}
