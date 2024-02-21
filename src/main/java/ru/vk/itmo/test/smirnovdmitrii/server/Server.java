package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class Server extends HttpServer {
    private static final int DEFAULT_FLUSH_VALUE_BYTES = 1024 * 1024; // 1 mb
    private static final String REQUEST_PATH = "/v0/entity";

    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private final DaoHttpServerConfig config;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    public Server(
            final DaoHttpServerConfig config
    ) throws IOException {
        super(config);
        this.config = config;
        startDao();
    }

    private void startDao() {
        final Config daoConfig = new Config(config.workingDir, DEFAULT_FLUSH_VALUE_BYTES);
        this.dao = new DaoImpl(daoConfig);
    }

    @Path(REQUEST_PATH)
    @RequestMethod(METHOD_GET)
    public Response get(
            @Param("id") final String id
    ) {
        return handleEntityRequest(id, key -> {
            final Entry<MemorySegment> entry = dao.get(key);
            if (entry == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }
            return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
        });
    }

    @Path(REQUEST_PATH)
    @RequestMethod(METHOD_PUT)
    public Response put(
            @Param("id") final String id,
            final Request request
    ) {
        return handleEntityRequest(id, key -> {
            final MemorySegment value = MemorySegment.ofArray(request.getBody());
            final Entry<MemorySegment> entry = new BaseEntry<>(key, value);
            dao.upsert(entry);
            return new Response(Response.CREATED, Response.EMPTY);
        });
    }

    @Path(REQUEST_PATH)
    @RequestMethod(METHOD_DELETE)
    public Response delete(
            @Param("id") final String id
    ) {
        return handleEntityRequest(id, key -> {
            final Entry<MemorySegment> entry = new BaseEntry<>(key, null);
            dao.upsert(entry);
            return new Response(Response.ACCEPTED, Response.EMPTY);
        });
    }

    private Response handleEntityRequest(
            final String id,
            final Function<MemorySegment, Response> keyToResponse
    ) {
        if (isInvalidKey(id)) {
            return new Response(Response.BAD_REQUEST, "invalid id".getBytes(StandardCharsets.UTF_8));
        }
        final MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        try {
            return keyToResponse.apply(key);
        } catch (final Exception e) {
            logger.error(e.getMessage(), e);
            return new Response(Response.INTERNAL_ERROR, e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void handleRequest(final Request request, final HttpSession session) throws IOException {
        final String path = request.getPath();
        final int method = request.getMethod();
        if (!validate(path, method, session)) {
            return;
        }
        final String id = request.getParameter("id=");
        final Response response = switch (method) {
            case METHOD_GET -> get(id);
            case METHOD_DELETE -> delete(id);
            case METHOD_PUT -> put(id, request);
            default -> new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        };
        session.sendResponse(response);
    }

    public boolean validate(String path, int method, HttpSession session) throws IOException {
        Response response = null;
        if (method != METHOD_GET && method != METHOD_PUT && method != METHOD_DELETE) {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        } else if (!path.startsWith(REQUEST_PATH)) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
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
