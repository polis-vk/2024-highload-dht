package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.DaoImpl;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtProperties;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.PropertyException;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.PropertyValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DaoHttpServer extends HttpServer {

    private static final String REQUEST_PATH = "/v0/entity";
    private static final byte[] INVALID_KEY_MESSAGE = "invalid id".getBytes(StandardCharsets.UTF_8);
    private static final Logger logger = LoggerFactory.getLogger(DaoHttpServer.class);

    private final DaoHttpServerConfig config;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    public DaoHttpServer(
            final DaoHttpServerConfig config,
            final Dao<MemorySegment, Entry<MemorySegment>> dao
    ) throws IOException {
        super(config);
        this.config = config;
        this.dao = dao;
    }

    public Response get(final MemorySegment key) {
        final Entry<MemorySegment> entry = dao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response put(
            final MemorySegment key,
            final Request request
    ) {
        final MemorySegment value = MemorySegment.ofArray(request.getBody());
        final Entry<MemorySegment> entry = new BaseEntry<>(key, value);
        dao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }


    public Response delete(final MemorySegment key) {
        final Entry<MemorySegment> entry = new BaseEntry<>(key, null);
        dao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(final Request request, final HttpSession session) throws IOException {
        final String uri = request.getURI();
        final String id = request.getParameter("id=");
        if (!validate(uri, id, session)) {
            return;
        }
        final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        final int method = request.getMethod();
        Response response;
        try {
            response = switch (method) {
                case METHOD_GET -> get(key);
                case METHOD_DELETE -> delete(key);
                case METHOD_PUT -> put(key, request);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (final Exception e) {
            logger.error("Exception while handling request", e);
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    public boolean validate(
            final String path,
            final String id,
            final HttpSession session
    ) throws IOException {
        Response response = null;
        if (!path.startsWith(REQUEST_PATH)) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else if (isInvalidKey(id)) {
            response = new Response(Response.BAD_REQUEST, INVALID_KEY_MESSAGE);
        }
        if (response == null) {
            return true;
        }
        session.sendResponse(response);
        return false;
    }

    public boolean isInvalidKey(final String key) {
        return key == null || key.isEmpty();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
