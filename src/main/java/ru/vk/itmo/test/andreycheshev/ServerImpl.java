package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.andreycheshev.dao.PersistentReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;
import static one.nio.http.Response.ACCEPTED;
import static one.nio.http.Response.BAD_REQUEST;
import static one.nio.http.Response.CREATED;
import static one.nio.http.Response.INTERNAL_ERROR;
import static one.nio.http.Response.METHOD_NOT_ALLOWED;
import static one.nio.http.Response.NOT_FOUND;
import static one.nio.http.Response.OK;

public class ServerImpl extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(ServerImpl.class);
    private static final String REQUEST_PATH = "/v0/entity";
    private static final String ID = "id=";

    private final PersistentReferenceDao dao;

    public ServerImpl(HttpServerConfig config, Config daoConfig) throws IOException {
        super(config);

        this.dao = new PersistentReferenceDao(daoConfig);
    }

    private Response get(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(fromString(id));

        return (entry == null)
                ? new Response(NOT_FOUND, Response.EMPTY)
                : new Response(OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response put(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                fromString(id),
                MemorySegment.ofArray(request.getBody())
        );
        dao.upsert(entry);

        return new Response(CREATED, Response.EMPTY);
    }

    private Response delete(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(fromString(id), null);
        dao.upsert(entry);

        return new Response(ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            sendResponse(
                    new Response(BAD_REQUEST, Response.EMPTY),
                    session
            );
            return;
        }

        int method = request.getMethod();

        Response response;
        try {
            response = switch (method) {
                case METHOD_GET -> get(request);
                case METHOD_PUT -> put(request);
                case METHOD_DELETE -> delete(request);
                default -> new Response(METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (Exception e) {
            logger.error("Internal error of the DAO operation", e);
            sendResponse(new Response(INTERNAL_ERROR, Response.EMPTY), session);
            return;
        }

        sendResponse(response, session);
    }

    private void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            throw new UncheckedIOException("Error while sending response to the client", e);
        }
    }

    private MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
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
}
