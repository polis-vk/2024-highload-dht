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
    private static final int DEFAULT_FLUSH_VALUE_BYTES = 16 * 1024; // 16 kb
    private static final String REQUEST_PATH = "/v0/entity";
    private static final Response INVALID_ID_RESPONSE = new Response(
            Response.BAD_REQUEST,
            "invalid id".getBytes(StandardCharsets.UTF_8)
    );
    private static final Response INVALID_REQUEST_RESPONSE = new Response(
            Response.BAD_REQUEST,
            new byte[0]
    );
    private static final Response INVALID_METHOD_RESPONSE = new Response(
            Response.METHOD_NOT_ALLOWED,
            new byte[0]
    );
    private static final Response CREATED_RESPONSE = new Response(
            Response.CREATED,
            new byte[0]
    );
    private static final Response ACCEPTED_RESPONSE = new Response(
            Response.ACCEPTED,
            new byte[0]
    );
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
                return new Response(Response.NOT_FOUND, new byte[0]);
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
            return CREATED_RESPONSE;
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
            return ACCEPTED_RESPONSE;
        });
    }

    private Response handleEntityRequest(
            final String id,
            final Function<MemorySegment, Response> keyToResponse
    ) {
        if (isInvalidKey(id)) {
            return INVALID_ID_RESPONSE;
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
    public void handleDefault(Request request, HttpSession session) throws IOException {
        final int method = request.getMethod();
        if (method == METHOD_GET || method == METHOD_PUT || method == METHOD_DELETE) {
            session.sendResponse(INVALID_REQUEST_RESPONSE);
        } else {
            session.sendResponse(INVALID_METHOD_RESPONSE);
        }
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
