package ru.vk.itmo.test.proninvalentin;

import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.proninvalentin.dao.ExtendedBaseEntry;
import ru.vk.itmo.test.proninvalentin.dao.ExtendedEntry;
import ru.vk.itmo.test.proninvalentin.dao.ReferenceDao;

import java.lang.foreign.MemorySegment;

public class RequestHandler {
    private final ReferenceDao dao;

    private static final String HTTP_TIMESTAMP_HEADER = Constants.HTTP_TIMESTAMP_HEADER + ":";

    public RequestHandler(ReferenceDao dao) {
        this.dao = dao;
    }

    public Response handle(Request request, String entryId) {
        return switch (request.getMethod()) {
            case Request.METHOD_PUT -> upsert(entryId, request);
            case Request.METHOD_GET -> get(entryId);
            case Request.METHOD_DELETE -> delete(entryId);
            default -> new Response(Response.BAD_REQUEST, Response.EMPTY);
        };
    }

    public Response upsert(@Param(value = "id", required = true) String id, Request request) {
        if (Utils.isNullOrBlank(id) || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegmentFactory.fromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new ExtendedBaseEntry<>(key, value, System.currentTimeMillis()));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response get(@Param(required = true, value = "id") String id) {
        if (Utils.isNullOrBlank(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegmentFactory.fromString(id);
        ExtendedEntry<MemorySegment> entry = dao.get(key);

        if (entry == null || entry.value() == null) {
            Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(HTTP_TIMESTAMP_HEADER + (entry != null ? entry.timestamp() : 0));
            return response;
        }

        Response response = new Response(Response.OK, MemorySegmentFactory.toByteArray(entry.value()));
        response.addHeader(HTTP_TIMESTAMP_HEADER + entry.timestamp());
        return response;
    }

    public Response delete(@Param(required = true, value = "id") String id) {
        if (Utils.isNullOrBlank(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        ExtendedEntry<MemorySegment> deletedMemorySegment =
                MemorySegmentFactory.toDeletedMemorySegment(id, System.currentTimeMillis());
        dao.upsert(deletedMemorySegment);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }
}
