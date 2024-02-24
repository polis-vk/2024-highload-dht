package ru.vk.itmo.test.andreycheshev;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.andreycheshev.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;
import static one.nio.http.Response.ACCEPTED;
import static one.nio.http.Response.BAD_REQUEST;
import static one.nio.http.Response.CREATED;
import static one.nio.http.Response.METHOD_NOT_ALLOWED;
import static one.nio.http.Response.NOT_FOUND;
import static one.nio.http.Response.OK;

public class RequestHandler {
    private static final String REQUEST_PATH = "/v0/entity";
    private static final String ID = "id=";
    private final ReferenceDao dao;

    public RequestHandler(ReferenceDao dao) {
        this.dao = dao;
    };

    public Response handle(Request request) throws IOException {

        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        int method = request.getMethod();

        return switch (method) {
            case METHOD_GET -> get(request);
            case METHOD_PUT -> put(request);
            case METHOD_DELETE -> delete(request);
            default -> new Response(METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }

    public Response get(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(fromString(id));

        return (entry == null)
                ? new Response(NOT_FOUND, Response.EMPTY)
                : new Response(OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response put(final Request request) {
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

    public Response delete(final Request request) {
        String id = request.getParameter(ID);
        if (id == null || id.isEmpty()) {
            return new Response(BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(fromString(id), null);
        dao.upsert(entry);

        return new Response(ACCEPTED, Response.EMPTY);
    }
    private MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
