package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.andreycheshev.dao.ClusterEntry;
import ru.vk.itmo.test.andreycheshev.dao.Dao;
import ru.vk.itmo.test.andreycheshev.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class RequestHandler implements HttpProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestHandler.class);
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
    private static final String ACK_PARAMETER = "ack=";
    private static final String FROM_PARAMETER = "from=";

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final RendezvousDistributor distributor;
    private final AsyncWebActions webActions;

    public RequestHandler(
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            RendezvousDistributor distributor) {

        this.dao = dao;
        this.distributor = distributor;
        this.webActions = new AsyncWebActions(this);
    }

    public void handle(Request request, HttpSession session) throws InterruptedException, IOException {

        // Checking the correctness.
        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            HttpUtils.sendBadRequest(session);
            return;
        }

        String id = request.getParameter(ID_PARAMETER);
        if (id == null || id.isEmpty()) {
            HttpUtils.sendBadRequest(session);
            return;
        }

        int method = request.getMethod();
        if (!AVAILABLE_METHODS.contains(method)) {
            HttpUtils.sendMethodNotAllowed(session);
            return;
        }

        // A timestamp is an indication that the request
        // came from the client directly or from the cluster node.
        String timestamp = request.getHeader(HttpUtils.TIMESTAMP_ONE_NIO_HEADER);
        if (timestamp != null) {
            // The request came from a remote node.
            try {

                CompletableFuture<Void> future =
                        webActions.processLocallyToSend(method, id, request, Long.parseLong(timestamp), session);

                assert future != null;

            } catch (AssertionError e) {
                HttpUtils.sendInternalError(session);
            } catch (NumberFormatException e) {
                HttpUtils.sendBadRequest(session);
            }
            return;
        }

        // Get and check "ack" and "from" parameters.
        ParametersParser parser = new ParametersParser(distributor);
        try {
            parser.parseAckFrom(
                    request.getParameter(ACK_PARAMETER),
                    request.getParameter(FROM_PARAMETER)
            );
        } catch (IllegalArgumentException e) {
            HttpUtils.sendBadRequest(session);
            return;
        }

        // Start processing on remote and local nodes.
        processDistributed(method, id, request, parser.getAck(), parser.getFrom(), session);
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
                    ? webActions.processLocallyToCollect(method, id, request, timestamp, collector)
                    : webActions.processRemotelyToCollect(node, request, timestamp, collector);

            if (future == null) {
                LOGGER.info("Async operations error");
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
