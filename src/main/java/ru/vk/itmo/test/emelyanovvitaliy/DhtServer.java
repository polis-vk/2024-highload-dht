package ru.vk.itmo.test.emelyanovvitaliy;

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
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DhtServer extends HttpServer {
    public static final byte[] EMPTY_BODY = new byte[0];
    private final ReferenceDao dao;

    public DhtServer(ServiceConfig config) throws IOException {
        super(createConfig(config));
        dao = new ReferenceDao(new Config(config.workingDir(), 1 << 24));
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @RequestMethod(METHOD_GET)
    @Path("/v0/entity")
    public Response entity(@Param(value = "id") String id) {
        if (isKeyIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, EMPTY_BODY);
        }
        Entry<MemorySegment> entry = dao.get(keyFor(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, EMPTY_BODY);
        }
        return new Response(Response.OK, valueFor(entry));
    }

    @RequestMethod(METHOD_PUT)
    @Path("/v0/entity")
    public Response putEntity(@Param(value = "id") String id, Request request) {
        if (isKeyIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, EMPTY_BODY);
        }
        dao.upsert(new BaseEntry<>(keyFor(id), MemorySegment.ofArray(request.getBody())));
        return new Response(Response.CREATED, EMPTY_BODY);
    }

    @RequestMethod(METHOD_DELETE)
    @Path("/v0/entity")
    public Response deleteEntity(@Param("id") String id) {
        if (isKeyIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, EMPTY_BODY);
        }
        dao.upsert(new BaseEntry<>(keyFor(id), null));
        return new Response(Response.ACCEPTED, EMPTY_BODY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        int requestMethod = request.getMethod();
        if (requestMethod == METHOD_GET || requestMethod == METHOD_PUT || requestMethod == METHOD_DELETE) {
            session.sendResponse(new Response(Response.BAD_REQUEST, EMPTY_BODY));
        } else {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, EMPTY_BODY));
        }
    }

    private static boolean isKeyIncorrect(String key) {
        return key == null || key.isEmpty();
    }

    private static HttpServerConfig createConfig(ServiceConfig serviceConfig) {
        HttpServerConfig config = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        config.acceptors = new AcceptorConfig[] {acceptorConfig};
        config.closeSessions = true;
        return config;
    }

    private static MemorySegment keyFor(String id) {
        return MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] valueFor(Entry<MemorySegment> entry) {
        return entry.value().toArray(ValueLayout.JAVA_BYTE);
    }

}
