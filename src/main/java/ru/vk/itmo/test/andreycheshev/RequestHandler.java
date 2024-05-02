package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.andreycheshev.dao.ClusterEntry;
import ru.vk.itmo.test.andreycheshev.dao.Dao;
import ru.vk.itmo.test.andreycheshev.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class RequestHandler implements HttpProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestHandler.class);

    private static final Set<Integer> AVAILABLE_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    ); // Immutable set.

    private static final String FUTURE_CREATION_ERROR = "Error when CompletableFuture creation";

    private static final String REQUEST_PATH = "/v0/entity";

    private static final String ID_PARAMETER = "id=";
    private static final String ACK_PARAMETER = "ack=";
    private static final String FROM_PARAMETER = "from=";

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

    public void sendAsync(Response response, HttpSession session) {
        try {
            asyncActions.sendAsync(response, session);
        } catch (AssertionError e) {
            LOGGER.info(FUTURE_CREATION_ERROR);
        }
    }

    private Response analyzeRequest(Request request, HttpSession session) {

        // Checking the correctness.
        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            return HttpUtils.getBadRequest();
        }

        String id = request.getParameter(ID_PARAMETER);
        if (id == null || id.isEmpty()) {
            return HttpUtils.getBadRequest();
        }

        int method = request.getMethod();
        if (!AVAILABLE_METHODS.contains(method)) {
            return HttpUtils.getMethodNotAllowed();
        }

        String timestamp = request.getHeader(HttpUtils.TIMESTAMP_ONE_NIO_HEADER);
        return isRequestCameFromNode(timestamp)
                ? local(request, method, id, timestamp, session)
                : distributed(request, method, id, session);
    }

    private Response local(Request request, int method, String id, String timestamp, HttpSession session) {
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

    private Response distributed(Request request, int method, String id, HttpSession session) {
        // Get and check "ack" and "from" parameters.
        ParametersParser parser = new ParametersParser(distributor);
        try {
            parser.parseAckFrom(
                    request.getParameter(ACK_PARAMETER),
                    request.getParameter(FROM_PARAMETER)
            );
        } catch (IllegalArgumentException e) {
            return HttpUtils.getBadRequest();
        }

        // Start processing on remote and local nodes.
        processDistributed(method, id, request, parser.getAck(), parser.getFrom(), session);
        return null;
    }

    private boolean isRequestCameFromNode(String timestamp) {
        // A timestamp is an indication that the request
        // came from the client directly or from the cluster node.
        return timestamp != null;
    }

    private void processDistributed(
            int method,
            String id,
            Request request,
            int ack,
            int from,
            HttpSession session) {

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

            if (future == null) {
                LOGGER.info(FUTURE_CREATION_ERROR);
            }
        }
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
