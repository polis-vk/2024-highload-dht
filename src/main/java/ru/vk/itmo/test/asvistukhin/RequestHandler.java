package ru.vk.itmo.test.asvistukhin;

import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.asvistukhin.dao.Dao;
import ru.vk.itmo.test.asvistukhin.dao.TimestampEntry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class RequestHandler {
    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;

    public RequestHandler(Dao<MemorySegment, TimestampEntry<MemorySegment>> dao) {
        this.dao = dao;
    }

    public Response handle(Request request) {
        String id = request.getParameter("id=");
        return switch (request.getMethod()) {
            case Request.METHOD_GET -> get(id);
            case Request.METHOD_PUT -> put(id, request);
            case Request.METHOD_DELETE -> delete(id);
            default -> new Response(Response.BAD_REQUEST, Response.EMPTY);
        };
    }

    public Response get(@Param(value = "id", required = true) String id) {
        if (RequestWrapper.isEmptyParam(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        TimestampEntry<MemorySegment> entry = dao.get(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)));

        Response response;
        long timestamp;
        if (entry == null || entry.value() == null) {
            timestamp = (entry != null ? entry.timestamp() : 0);
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(RequestWrapper.TIMESTAMP_STRING_HEADER + timestamp);
        } else {
            timestamp = entry.timestamp();
            response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            response.addHeader(RequestWrapper.TIMESTAMP_STRING_HEADER + timestamp);
        }

        return response;
    }

    public Response put(@Param(value = "id", required = true) String id, Request request) {
        if (RequestWrapper.isEmptyParam(id) || RequestWrapper.isEmptyRequest(request)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new TimestampEntry<>(key, value, System.currentTimeMillis()));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response delete(@Param(value = "id", required = true) String id) {
        if (RequestWrapper.isEmptyParam(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        dao.upsert(
            new TimestampEntry<>(
                MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                null,
                System.currentTimeMillis())
        );

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }
}
