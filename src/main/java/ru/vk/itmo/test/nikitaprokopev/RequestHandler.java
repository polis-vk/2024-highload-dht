package ru.vk.itmo.test.nikitaprokopev;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.nikitaprokopev.dao.BaseEntry;
import ru.vk.itmo.test.nikitaprokopev.dao.Dao;
import ru.vk.itmo.test.nikitaprokopev.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class RequestHandler {
    private static final String BASE_PATH = "/v0/entity";
    private static final String TRUE = "true";
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public RequestHandler(Dao<MemorySegment, Entry<MemorySegment>> dao) {
        this.dao = dao;
    }

    public Collection<HttpRequest> createRequests(
            Request request, String key, Collection<String> targetNodes, String selfUrl) {
        List<HttpRequest> httpRequests = new ArrayList<>(targetNodes.size());
        long timestamp = System.currentTimeMillis();
        for (String node : targetNodes) {
            httpRequests.add(buildHttpRequest(key, node, request, timestamp, selfUrl));
        }
        return httpRequests;
    }

    public Response handleGet(String id) {
        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = dao.get(msKey);

        // entry does not exist
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        // entry is tombstone
        if (entry.value() == null) {
            Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(MyServer.HEADER_TIMESTAMP + entry.timestamp());
            return response;
        }

        Response response = Response.ok(toByteArray(entry.value()));
        response.addHeader(MyServer.HEADER_TIMESTAMP + entry.timestamp());
        return response;
    }

    public Response handlePut(String id, byte[] body, long timestamp) {
        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = new BaseEntry<>(msKey, MemorySegment.ofArray(body), timestamp);

        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response handleDelete(String id, long timestamp) {
        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = new BaseEntry<>(msKey, null, timestamp);

        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    public Iterator<Entry<MemorySegment>> handleEntities(String start, String end) {
        MemorySegment msStart = toMemorySegment(start);
        MemorySegment msEnd = toMemorySegment(end);

        return dao.get(msStart, msEnd);
    }

    private HttpRequest buildHttpRequest(
            String key, String targetNode, Request request, long timestamp, String selfUrl) {
        HttpRequest.Builder httpRequest = HttpRequest.newBuilder(URI.create(targetNode + BASE_PATH + "?id=" + key));
        switch (request.getMethod()) {
            case Request.METHOD_GET -> httpRequest.GET();
            case Request.METHOD_PUT -> httpRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
            case Request.METHOD_DELETE -> httpRequest.DELETE();
            default -> throw new IllegalArgumentException("Unsupported method: " + request.getMethod());
        }
        httpRequest.setHeader(MyServer.HEADER_INTERNAL, TRUE);
        httpRequest.setHeader(MyServer.HEADER_TIMESTAMP_LOWER_CASE, String.valueOf(timestamp));
        if (targetNode.equals(selfUrl)) {
            httpRequest.setHeader(MyServer.LOCAL_REQUEST, TRUE);
        }
        return httpRequest.build();
    }

    private MemorySegment toMemorySegment(String s) {
        if (s == null) {
            return null;
        }
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toByteArray(MemorySegment ms) {
        if (ms == null) {
            return null;
        }
        return ms.toArray(ValueLayout.JAVA_BYTE);
    }
}
