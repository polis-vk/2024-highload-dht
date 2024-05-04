package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestHandler.class);

    public static final String FUTURE_CREATION_ERROR = "Error when CompletableFuture creation";

    private static final Set<Integer> AVAILABLE_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );

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

        String path = request.getPath();
        if (!path.equals(ENTITY_REQUEST_PATH)) {
            return HttpUtils.getBadRequest();
        }

        int method = request.getMethod();
        if (!AVAILABLE_METHODS.contains(method)) {
            return HttpUtils.getMethodNotAllowed();
        }

        try {

            String id = ParametersParser.parseId(request);
            String timestamp = request.getHeader(HttpUtils.TIMESTAMP_ONE_NIO_HEADER);

            return isRequestCameFromNode(timestamp)
                    ? processLocally(request, method, id, timestamp, session)
                    : processDistributed(request, method, id, session);

        } catch (IllegalArgumentException e) {
            return HttpUtils.getBadRequest();
        }
    }

    private Response processLocally(Request request, int method, String id, String timestamp, HttpSession session) {
        try {
            CompletableFuture<Void> future =
                    asyncActions.processLocallyToSend(method, id, request, Long.parseLong(timestamp), session);

            if (future == null) {
                LOGGER.info(FUTURE_CREATION_ERROR);
                return HttpUtils.getInternalError();
            }
        } catch (NumberFormatException e) {
            return HttpUtils.getBadRequest();
        }
        return null;
    }

    private Response processDistributed(
            Request request,
            int method,
            String id,
            HttpSession session) {

        ParametersTuple<Integer> ackFrom = ParametersParser.parseAckFromOrDefault(request, distributor);
        int ack = ackFrom.first();
        int from = ackFrom.second();
        if (ack <= 0 || ack > from) {
            LOGGER.error("An error occurred while analyzing the parameters");
            return HttpUtils.getBadRequest();
        }

        List<Integer> nodesIndices = distributor.getNodesByKey(id, from);

        long timestamp = method == Request.METHOD_GET
                ? HttpUtils.EMPTY_TIMESTAMP
                : System.currentTimeMillis();

        ResponseCollector collector = new ResponseCollector(method, ack, from);

        for (int nodeIndex : nodesIndices) {
            String node = distributor.getNodeUrlByIndex(nodeIndex);

            CompletableFuture<Void> future = distributor.isOurNode(nodeIndex)
                    ? asyncActions.processLocallyToCollect(method, id, request, timestamp, collector, session)
                    : asyncActions.processRemotelyToCollect(node, request, timestamp, collector, session);

            if (future == null) {
                LOGGER.info(FUTURE_CREATION_ERROR);
                return HttpUtils.getInternalError();
            }
        }

        return null;
    }

    private boolean isRequestCameFromNode(String timestamp) {
        // A timestamp is an indication that the request
        // came from the client directly or from the cluster node.
        return timestamp != null;
    }

    private Response analyzeEntities(Request request, HttpSession session) {
        try {
            ParametersTuple<String> params = ParametersParser.parseStartEnd(request);
            String start = params.first();
            String end = params.second();
            if (start == null || start.isEmpty() || (end != null && end.isEmpty())) {
                throw new IllegalArgumentException();
            }

            Iterator<Entry<MemorySegment>> streamingIterator = dao.get(
                    fromString(start),
                    end == null ? null : fromString(end)
            );

            CompletableFuture<Void> future = asyncActions.stream(
                    () -> {
                        try {
                            ((StreamingSession) session).stream(streamingIterator);
                        } catch (Exception e) {
                            LOGGER.error("An error occurred while streaming response");
                        }
                    }
            );

            if (future == null) {
                LOGGER.info(FUTURE_CREATION_ERROR);
                return HttpUtils.getInternalError();
            }
        } catch (IllegalArgumentException e) {
            LOGGER.error("An error occurred while analyzing entities the parameters");
            return HttpUtils.getBadRequest();
        }

        return null;
    }

    public void sendAsync(Response response, HttpSession session) {
        CompletableFuture<Void> future = asyncActions.sendAsync(response, session);
        if (future == null) {
            LOGGER.info(FUTURE_CREATION_ERROR);
        }
    }

    @Override
    public ResponseElements get(String id) {
        ClusterEntry<MemorySegment> entry = (ClusterEntry<MemorySegment>) dao.get(fromString(id));

        ResponseElements response;
        if (entry == null) {
            response = new ResponseElements(HttpUtils.NOT_FOUND_CODE, Response.EMPTY, HttpUtils.EMPTY_TIMESTAMP);
        } else {
            long timestamp = entry.timestamp();
            response = (entry.value() == null)
                    ? new ResponseElements(HttpUtils.GONE_CODE, Response.EMPTY, timestamp)
                    : new ResponseElements(HttpUtils.OK_CODE, entry.value().toArray(ValueLayout.JAVA_BYTE), timestamp);
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

        return new ResponseElements(HttpUtils.CREATED_CODE, Response.EMPTY, HttpUtils.EMPTY_TIMESTAMP);
    }

    @Override
    public ResponseElements delete(String id, long timestamp) {
        Entry<MemorySegment> entry = new ClusterEntry<>(
                fromString(id),
                null,
                timestamp
        );
        dao.upsert(entry);

        return new ResponseElements(HttpUtils.ACCEPT_CODE, Response.EMPTY, HttpUtils.EMPTY_TIMESTAMP);
    }

    private static MemorySegment fromString(String data) {
        LOGGER.info(data);
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
