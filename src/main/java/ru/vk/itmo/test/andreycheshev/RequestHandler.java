package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public class RequestHandler {
    private static final Set<Integer> AVAILABLE_METHODS;

    static {
        AVAILABLE_METHODS = Set.of(
                Request.METHOD_GET,
                Request.METHOD_PUT,
                Request.METHOD_DELETE
        ); // Immutable set.
    }

    private static final String REQUEST_PATH = "/v0/entity";
    private static final String ID_PARAMETER = "id=";

    private final RendezvousDistributor distributor;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final HttpClient[] clusterConnections;

    public RequestHandler(Dao<MemorySegment, Entry<MemorySegment>> dao,
                          HttpClient[] clusterConnections,
                          RendezvousDistributor distributor) {
        this.dao = dao;
        this.clusterConnections = clusterConnections;
        this.distributor = distributor;
    }

    public Response handle(Request request) throws HttpException, IOException, PoolException, InterruptedException {

        // Checking the correctness.
        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String id = request.getParameter(ID_PARAMETER);
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        int method = request.getMethod();
        if (!AVAILABLE_METHODS.contains(method)) {
            return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }

        int currNodeNumber = distributor.getNode(id);
        if (currNodeNumber >= 0) {
            // Redirect request, processing on another node.
            HttpClient client = clusterConnections[currNodeNumber];
            return client.invoke(request);
        }

        // Processing locally.
        return switch (method) {
            case Request.METHOD_GET -> get(id);
            case Request.METHOD_PUT -> put(id, request.getBody());
            default -> delete(id); // The check was earlier, so here the delete method.
        };
    }

    private Response get(String id) {
        Entry<MemorySegment> entry = dao.get(fromString(id));

        return entry == null
                ? new Response(Response.NOT_FOUND, Response.EMPTY)
                : new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response put(String id, byte[] body) {
        Entry<MemorySegment> entry = new BaseEntry<>(
                fromString(id),
                MemorySegment.ofArray(body)
        );
        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response delete(String id) {
        Entry<MemorySegment> entry = new BaseEntry<>(fromString(id), null);
        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
