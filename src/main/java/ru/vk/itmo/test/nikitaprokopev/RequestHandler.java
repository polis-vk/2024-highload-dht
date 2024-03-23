package ru.vk.itmo.test.nikitaprokopev;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.nikitaprokopev.dao.BaseEntry;
import ru.vk.itmo.test.nikitaprokopev.dao.Dao;
import ru.vk.itmo.test.nikitaprokopev.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RequestHandler {
    private static final String BASE_PATH = "/v0/entity";
    private static final String HEADER_TIMESTAMP = "X-Timestamp: ";
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public RequestHandler(Dao<MemorySegment, Entry<MemorySegment>> dao) {
        this.dao = dao;
    }

    public List<HttpRequest> createInternalRequests(
            Request request,
            String key,
            List<String> targetNodes,
            ServiceConfig serviceConfig
    ) {
        List<HttpRequest> httpRequests = new ArrayList<>();
        for (String node : targetNodes) {
            if (node.equals(serviceConfig.selfUrl())) {
                httpRequests.add(null);
                continue;
            }
            HttpRequest subRequest = buildHttpRequest(key, node, request);
            httpRequests.add(subRequest);
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
            response.addHeader(HEADER_TIMESTAMP + entry.timestamp());
            return response;
        }

        Response response = Response.ok(toByteArray(entry.value()));
        response.addHeader(HEADER_TIMESTAMP + entry.timestamp());
        return response;
    }

    public Response handlePut(String id, byte[] body) {
        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                MemorySegment.ofArray(body),
                System.currentTimeMillis()
        );

        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response handleDelete(String id) {
        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = new BaseEntry<>(
                msKey,
                null,
                System.currentTimeMillis()
        );

        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private HttpRequest buildHttpRequest(String key, String targetNode, Request request) {
        HttpRequest.Builder httpRequest = HttpRequest.newBuilder(URI.create(targetNode + BASE_PATH + "?id=" + key));
        switch (request.getMethod()) {
            case Request.METHOD_GET -> httpRequest.GET();
            case Request.METHOD_PUT -> httpRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
            case Request.METHOD_DELETE ->  httpRequest.DELETE();
            default -> throw new IllegalArgumentException("Unsupported method: " + request.getMethod());
        }
        httpRequest.setHeader("X-Internal", "true");
        return httpRequest.build();
    }

    private MemorySegment toMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toByteArray(MemorySegment ms) {
        return ms.toArray(ValueLayout.JAVA_BYTE);
    }
}
