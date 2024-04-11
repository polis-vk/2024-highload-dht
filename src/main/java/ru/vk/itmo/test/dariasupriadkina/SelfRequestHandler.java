package ru.vk.itmo.test.dariasupriadkina;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedBaseEntry;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedEntry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class SelfRequestHandler {

    private static final String TIMESTAMP_MILLIS_HEADER = "X-TIMESTAMP-MILLIS: ";
    private final Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao;
    private final Utils utils;
    private final ExecutorService delegateExecutor;

    public SelfRequestHandler(Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao,
                              Utils utils, ExecutorService delegateExecutor) {
        this.dao = dao;
        this.utils = utils;
        this.delegateExecutor = delegateExecutor;
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
            case "GET" -> CompletableFuture.supplyAsync(() -> get(id), delegateExecutor);
            case "PUT" -> CompletableFuture.supplyAsync(() -> put(id, request), delegateExecutor);
            case "DELETE" -> CompletableFuture.supplyAsync(() -> delete(id), delegateExecutor);
            default -> CompletableFuture.supplyAsync(() ->
                    new Response(Response.NOT_FOUND, Response.EMPTY), delegateExecutor);
        };
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
}
