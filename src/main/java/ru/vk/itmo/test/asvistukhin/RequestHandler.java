package ru.vk.itmo.test.asvistukhin;

import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.asvistukhin.dao.Dao;
import ru.vk.itmo.test.asvistukhin.dao.TimestampEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestHandler {

    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;

    public RequestHandler(Dao<MemorySegment, TimestampEntry<MemorySegment>> dao) {
        this.dao = dao;
    }

    public Response handleEntity(Request request) {
        String id = request.getParameter("id=");
        return switch (request.getMethod()) {
            case Request.METHOD_GET -> get(id);
            case Request.METHOD_PUT -> put(id, request);
            case Request.METHOD_DELETE -> delete(id);
            default -> new Response(Response.BAD_REQUEST, Response.EMPTY);
        };
    }

    public void handleEntities(Request request, HttpSession session) {
        String start = request.getParameter("start=");
        String end = request.getParameter("end=");
        try {
            switch (request.getMethod()) {
                case Request.METHOD_GET -> rangeGet(start, end, session);
                default -> session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            }
        } catch (IOException e) {
            session.close();
        }
    }

    public CompletableFuture<Void> handle(
        Request request,
        List<Response> collectedResponses,
        AtomicInteger unsuccessfulResponsesCount
    ) {
        return CompletableFuture.runAsync(() -> {
                Response response = handleEntity(request);
                if (ServerImpl.isSuccessProcessed(response.getStatus())) {
                    collectedResponses.add(response);
                } else {
                    unsuccessfulResponsesCount.incrementAndGet();
                }
            }
        );
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

    public void rangeGet(
        @Param(value = "start", required = true) String start,
        @Param(value = "end") String end,
        HttpSession session
    ) throws IOException {
        if (RequestWrapper.isEmptyParam(start)) {
            session.sendError(Response.BAD_REQUEST, null);
        }

        Iterator<TimestampEntry<MemorySegment>> entries = dao.get(
            MemorySegment.ofArray(start.getBytes(StandardCharsets.UTF_8)),
            end == null || end.isEmpty() ? null : MemorySegment.ofArray(end.getBytes(StandardCharsets.UTF_8))
        );

        ChunkWriter.writeAllEntries(session, entries);
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
