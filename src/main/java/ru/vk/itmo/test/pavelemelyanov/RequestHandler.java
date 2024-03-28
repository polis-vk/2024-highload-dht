package ru.vk.itmo.test.pavelemelyanov;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.pavelemelyanov.dao.BaseEntryWithTimestamp;
import ru.vk.itmo.test.pavelemelyanov.dao.Dao;
import ru.vk.itmo.test.pavelemelyanov.dao.EntryWithTimestamp;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static ru.vk.itmo.test.pavelemelyanov.HeaderUtils.HTTP_TIMESTAMP_HEADER;

public class RequestHandler {
    private final Dao dao;

    public RequestHandler(Dao dao) {
        this.dao = dao;
    }

    public Response handle(Request request, String id) {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        return switch (request.getMethod()) {
            case Request.METHOD_GET -> getEntry(id);
            case Request.METHOD_PUT -> putEntry(id, request);
            case Request.METHOD_DELETE -> deleteEntry(id);
            default -> new Response(Response.BAD_REQUEST, Response.EMPTY);
        };
    }

    private Response getEntry(String id) {
        MemorySegment key = convertFromString(id);
        EntryWithTimestamp<MemorySegment> entry = dao.get(key);

        if (entry == null || entry.value() == null) {
            long timestamp = entry != null ? entry.timestamp() : 0;
            return sendResponseWithTimestamp(
                    Response.NOT_FOUND,
                    Response.EMPTY,
                    timestamp
            );
        }

        return sendResponseWithTimestamp(
                Response.OK,
                entry.value().toArray(ValueLayout.JAVA_BYTE),
                entry.timestamp()
        );
    }

    private Response putEntry(String id, Request request) {
        if (request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = convertFromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());

        dao.upsert(new BaseEntryWithTimestamp(key, value, System.currentTimeMillis()));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response deleteEntry(String id) {
        MemorySegment key = convertFromString(id);
        dao.upsert(new BaseEntryWithTimestamp<>(key, null, System.currentTimeMillis()));

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Response sendResponseWithTimestamp(String resultCode, byte[] body, long timestamp) {
        Response response = new Response(resultCode, body);
        response.addHeader(HTTP_TIMESTAMP_HEADER + timestamp);
        return response;
    }

    private static MemorySegment convertFromString(String value) {
        return MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8));
    }
}
