package ru.vk.itmo.test.dariasupriadkina;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedBaseEntry;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

public class SelfRequestHandler {

    private static final String TIMESTAMP_MILLIS_HEADER = "X-TIMESTAMP-MILLIS: ";
    private final Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao;
    private final Utils utils;

    public SelfRequestHandler(Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao, Utils utils) {
        this.dao = dao;
        this.utils = utils;
    }

    public Response handleRequest(Request request) {
        String id = utils.getIdParameter(request);
        return switch (request.getMethodName()) {
            case "GET" -> get(id);
            case "PUT" -> put(id, request);
            case "DELETE" -> delete(id);
            default -> new Response(Response.NOT_FOUND, Response.EMPTY);
        };
    }

    public CompletableFuture<Response> handleAsyncRequest(Request request) {
        String id = utils.getIdParameter(request);
        return switch (request.getMethodName()) {
            case "GET" -> composeFuture(get(id));
            case "PUT" -> composeFuture(put(id, request));
            case "DELETE" -> composeFuture(delete(id));
            default -> composeFuture(new Response(Response.NOT_FOUND, Response.EMPTY));
        };
    }

    private CompletableFuture<Response> composeFuture(Response response) {
        return CompletableFuture.completedFuture(response);
    }

    public Response get(String id) {
        try {
            if (id == null || id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            Response response;
            ExtendedEntry<MemorySegment> entry = utils.getEntryById(id);
            if (entry == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            } else if (entry.value() == null) {
                response = new Response(Response.NOT_FOUND, Response.EMPTY);
                response.addHeader(TIMESTAMP_MILLIS_HEADER + entry.timestampMillis());
                return response;
            }
            response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            response.addHeader(TIMESTAMP_MILLIS_HEADER + entry.timestampMillis());
            return response;
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public Response put(String id, Request request) {
        try {
            if (id == null || id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            BaseEntry<MemorySegment> baseEntry = (BaseEntry<MemorySegment>) utils.convertToEntry(id, request.getBody());
            ExtendedEntry<MemorySegment> extendedEntry =
                    new ExtendedBaseEntry<>(baseEntry.key(), baseEntry.value(), System.currentTimeMillis());
            dao.upsert(extendedEntry);
            Response response = new Response(Response.CREATED, Response.EMPTY);
            response.addHeader(TIMESTAMP_MILLIS_HEADER + extendedEntry.timestampMillis());
            return response;
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public Response delete(String id) {
        try {
            if (id == null || id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            BaseEntry<MemorySegment> baseEntry = (BaseEntry<MemorySegment>) utils.convertToEntry(id, null);
            ExtendedEntry<MemorySegment> extendedEntry =
                    new ExtendedBaseEntry<>(baseEntry.key(), baseEntry.value(), System.currentTimeMillis());
            dao.upsert(extendedEntry);
            Response response = new Response(Response.ACCEPTED, Response.EMPTY);
            response.addHeader(TIMESTAMP_MILLIS_HEADER + extendedEntry.timestampMillis());
            return response;
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public void handleRange(Request request, HttpSession session) throws IOException {
        String start = request.getParameter("start=");
        String end = request.getParameter("end=");

        if (start == null
                || request.getMethod() != Request.METHOD_GET
                || start.isEmpty()
                || (end != null && end.isEmpty())) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        Iterator<ExtendedEntry<MemorySegment>> it = dao.get(
                utils.convertByteArrToMemorySegment(start.getBytes(StandardCharsets.UTF_8)),
                end == null ? null :
                        utils.convertByteArrToMemorySegment(end.getBytes(StandardCharsets.UTF_8))
        );

        sendRange(it, session);
    }

    public void sendRange(Iterator<ExtendedEntry<MemorySegment>> it, HttpSession session) throws IOException {
        ByteArrayBuilder bb = new ByteArrayBuilder();
        bb.append(EntryChunkUtils.HEADER_BYTES, 0, EntryChunkUtils.HEADER_BYTES.length);
        while (it.hasNext()) {
            ExtendedEntry<MemorySegment> ee = it.next();
            EntryChunkUtils.getEntryByteChunk(ee, bb);
        }
        bb.append(EntryChunkUtils.LAST_BYTES, 0, EntryChunkUtils.LAST_BYTES.length);
        session.write(bb.toBytes(), 0, bb.length());
        session.scheduleClose();
    }

}
