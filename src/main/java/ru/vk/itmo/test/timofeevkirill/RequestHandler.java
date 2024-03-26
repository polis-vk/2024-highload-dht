package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.timofeevkirill.dao.BaseTimestampEntry;
import ru.vk.itmo.test.timofeevkirill.dao.TimestampEntry;
import ru.vk.itmo.test.timofeevkirill.dao.Dao;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class RequestHandler {
    public static final String HTTP_TIMESTAMP_HEADER = "X-Timestamp";
    private final Dao dao;

    public RequestHandler(Dao dao) {
        this.dao = dao;
    }

    public Response handle(Request request, String entryId) {
        return switch (request.getMethod()) {
            case Request.METHOD_PUT -> put(entryId, request);
            case Request.METHOD_GET -> get(entryId);
            case Request.METHOD_DELETE -> delete(entryId);
            default -> new Response(Response.BAD_REQUEST, Response.EMPTY);
        };
    }

    public Response get(@Param(value = "id", required = true) String id) {
        if (RequestParameters.isEmptyParam(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        TimestampEntry<MemorySegment> entry = dao.get(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)));

        Response response;
        if (entry == null || entry.value() == null) {
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(HTTP_TIMESTAMP_HEADER + (entry != null ? entry.timestamp() : 0));
        } else {
            response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            response.addHeader(HTTP_TIMESTAMP_HEADER + entry.timestamp());
        }

        return response;
    }

    public Response put(@Param(value = "id", required = true) String id, Request request) {
        if (RequestParameters.isEmptyParam(id)
                || RequestParameters.isEmptyRequest(request))
            return new Response(Response.BAD_REQUEST, Response.EMPTY);

        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseTimestampEntry(key, value, System.currentTimeMillis()));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response delete(@Param(value = "id", required = true) String id) {
        if (RequestParameters.isEmptyParam(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        dao.upsert(new BaseTimestampEntry(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)), null, System.currentTimeMillis()));

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }
}