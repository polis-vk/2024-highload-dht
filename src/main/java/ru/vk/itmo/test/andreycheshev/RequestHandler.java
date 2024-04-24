package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.andreycheshev.dao.ClusterEntry;
import ru.vk.itmo.test.andreycheshev.dao.Dao;
import ru.vk.itmo.test.andreycheshev.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class RequestHandler implements HttpProvider {
    private static final Set<Integer> AVAILABLE_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    ); // Immutable set.

    private static final String ID_PARAMETER = "id=";

    private static final String ENTITY_REQUEST_PATH = "/v0/entity";
    private static final String ENTITIES_REQUEST_PATH = "/v0/entities";

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final RendezvousDistributor distributor;
    private final AsyncActions asyncActions;

    public RequestHandler(
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            RendezvousDistributor distributor) {

        this.dao = dao;
        this.distributor = distributor;
        this.asyncActions = new AsyncActions(this);
    }

    public void handle(Request request, HttpSession session) {
        Response errorResponse = analyzeRequest(request, session);

        if (errorResponse != null) {
            sendAsync(errorResponse, session);
        }
    }

    private Response analyzeRequest(Request request, HttpSession session) {
        if (!AVAILABLE_METHODS.contains(request.getMethod())) {
            return HttpUtils.getMethodNotAllowed();
        }

        return switch (request.getPath()) {
            case ENTITY_REQUEST_PATH -> analyzeEntity(request, session);
            case ENTITIES_REQUEST_PATH -> analyzeEntities(request, session);
            default -> HttpUtils.getBadRequest();
        };
    }

    private Response analyzeEntity(Request request, HttpSession session) {
        String id = request.getParameter(ID_PARAMETER);
        if (id == null || id.isEmpty()) {
            return HttpUtils.getBadRequest();
        }

        String timestamp = request.getHeader(HttpUtils.TIMESTAMP_ONE_NIO_HEADER);
        try {
            if (isRequestCameFromNode(timestamp)) {
                // The request came from a remote node.
                CompletableFuture<Void> future =
                        asyncActions.processLocallyToSend(id, request, Long.parseLong(timestamp), session);

                if (AsyncActions.checkFuture(future)) {
                    return HttpUtils.getInternalError();
                }
            } else {
                // Get and check "ack" and "from" parameters.
                ParametersTuple<Integer> params = ParametersParser.parseAckFrom(request, distributor);

                // Start processing on remote and local nodes.
                processDistributed(id, request, params.first(), params.second(), session);
            }
        } catch (IllegalArgumentException e) {
            return HttpUtils.getBadRequest();
        }

        return null;
    }

    private Response analyzeEntities(Request request, HttpSession session) {
        try {
            // Get and check "start" and "end" parameters.
            ParametersTuple<String> params = ParametersParser.parseStartEnd(request);

            Iterator<Entry<MemorySegment>> streamingIterator = dao.get(
                    fromString(params.first()),
                    fromString(params.second())
            );

            asyncActions.stream(
                    () -> {
                        try {
                            ((StreamingSession) session).stream(streamingIterator);
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                        }
                    }
            );
        } catch (IllegalArgumentException e) {
            return HttpUtils.getBadRequest();
        }

        return null;
    }

    private void processDistributed(
            String id,
            Request request,
            int ack,
            int from,
            HttpSession session) {

        int method = request.getMethod();

        List<Integer> nodesIndices = distributor.getQuorumNodes(id, from);

        long timestamp = (method == Request.METHOD_GET)
                ? HttpUtils.EMPTY_TIMESTAMP
                : System.currentTimeMillis();

        ResponseCollector collector = new ResponseCollector(method, ack, from, session);

        for (int nodeIndex : nodesIndices) {
            String node = distributor.getNodeUrlByIndex(nodeIndex);

            CompletableFuture<Void> future = distributor.isOurNode(nodeIndex)
                    ? asyncActions.processLocallyToCollect(method, id, request, timestamp, collector)
                    : asyncActions.processRemotelyToCollect(node, request, timestamp, collector);

            AsyncActions.checkFuture(future);
        }
    }

    private boolean isRequestCameFromNode(String timestamp) {
        // A timestamp is an indication that the request
        // came from the client directly or from the cluster node.
        return timestamp != null;
    }

    public void sendAsync(Response response, HttpSession session) {
        asyncActions.sendAsync(response, session);
    }

    @Override
    public ResponseElements get(String id) {
        ClusterEntry<MemorySegment> entry = (ClusterEntry<MemorySegment>) dao.get(fromString(id));

        ResponseElements response;
        if (entry == null) {
            response = new ResponseElements(404, Response.EMPTY, HttpUtils.EMPTY_TIMESTAMP);
        } else {
            long timestamp = entry.timestamp();
            response = (entry.value() == null)
                    ? new ResponseElements(410, Response.EMPTY, timestamp)
                    : new ResponseElements(200, entry.value().toArray(ValueLayout.JAVA_BYTE), timestamp);
        }

        return response;
    }

    @Override
    public ResponseElements put(String id, byte[] body, long timestamp) {
        Entry<MemorySegment> entry = new ClusterEntry<>(
                fromString(id),
                MemorySegment.ofArray(body),
                timestamp
        );
        dao.upsert(entry);

        return new ResponseElements(201, Response.EMPTY, HttpUtils.EMPTY_TIMESTAMP);
    }

    @Override
    public ResponseElements delete(String id, long timestamp) {
        Entry<MemorySegment> entry = new ClusterEntry<>(
                fromString(id),
                null,
                timestamp
        );
        dao.upsert(entry);

        return new ResponseElements(202, Response.EMPTY, HttpUtils.EMPTY_TIMESTAMP);
    }

    private static MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
