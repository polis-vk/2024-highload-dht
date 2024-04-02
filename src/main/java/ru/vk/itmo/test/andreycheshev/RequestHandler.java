package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.andreycheshev.dao.ClusterEntry;
import ru.vk.itmo.test.andreycheshev.dao.Dao;
import ru.vk.itmo.test.andreycheshev.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RequestHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestHandler.class);
    private static final TimestampComparator timestampComparator = new TimestampComparator();
    private static final Set<Integer> AVAILABLE_METHODS;

    static {
        AVAILABLE_METHODS = Set.of(
                Request.METHOD_GET,
                Request.METHOD_PUT,
                Request.METHOD_DELETE
        ); // Immutable set.
    }

    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String REQUEST_PATH = "/v0/entity";

    private static final String ID_PARAMETER = "id=";
    private static final String ACK_PARAMETER = "ack=";
    private static final String FROM_PARAMETER = "from=";

    public static final String TIMESTAMP_HEADER = "Timestamp: ";
    private static final String CONTENT_LENGTH_HEADER = "Content-Length: ";

    private static final int OK = 200;
    private static final int CREATED = 201;
    private static final int ACCEPTED = 202;
    private static final int NOT_FOUND = 404;
    private static final int GONE = 410;

    private static final int EMPTY_TIMESTAMP = -1;

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final HttpClient[] clusterConnections;
    private final RendezvousDistributor distributor;

    public RequestHandler(
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            HttpClient[] clusterConnections,
            RendezvousDistributor distributor) {

        this.dao = dao;
        this.clusterConnections = clusterConnections;
        this.distributor = distributor;
    }

    public Response handle(Request request) throws InterruptedException {

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

        // A timestamp is an indication that the request
        // came from the client directly or from the cluster node.
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (timestamp != null) {
            // The request came from a remote node.
            return processLocally(method,
                    id,
                    request,
                    Long.parseLong(timestamp)
            );
        }

        ParametersParser parser= new ParametersParser(distributor);
        try {
            parser.parseAckFrom(
                    request.getParameter(ACK_PARAMETER),
                    request.getParameter(FROM_PARAMETER)
            );
        } catch (IllegalArgumentException e) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        // Start processing on remote and local nodes.
        return processDistributed(method, id, request, parser.getAck(), parser.getFrom());
    }

    private Response processDistributed(
            int method,
            String id,
            Request request,
            int ack,
            int from) throws InterruptedException {

        ArrayList<Integer> nodes = distributor.getQuorumNodes(id, from);
        List<Response> responses = new ArrayList<>();

        long currTimestamp = (method == Request.METHOD_GET)
                ? EMPTY_TIMESTAMP
                : System.currentTimeMillis();

        for (int node : nodes) {
            try {
                Response response = distributor.isOurNode(node)
                        ? processLocally(method, id, request, currTimestamp)
                        : redirectRequest(method, node, request, currTimestamp);

                if (isRequestSucceeded(method, response.getStatus())) {
                    responses.add(response);
                }

                // For the GET method, we do not need to continue to request
                // if we have already received the required number of successful responses
                if (method == Request.METHOD_GET && areEnoughSuccessfulResponses(responses, ack)) {
                    return analyzeResponses(method, responses);
                }
            } catch (SocketTimeoutException e) {
                LOGGER.error("Request processing time exceeded on another node", e);
            } catch (PoolException | HttpException | IOException e) {
                LOGGER.error("An error occurred when processing a request on another node", e);
            }
        }

        return areEnoughSuccessfulResponses(responses, ack)
                ? new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY)
                : analyzeResponses(method, responses);
    }

    private static boolean isRequestSucceeded(int method, int status) {
        return (method == Request.METHOD_GET && (status == OK || status == NOT_FOUND || status == GONE))
                || (method == Request.METHOD_PUT && status == CREATED)
                || (method == Request.METHOD_DELETE && status == ACCEPTED);
    }

    private static boolean areEnoughSuccessfulResponses(List<Response> responses, int ack) {
        return responses.size() == ack;
    }

    private Response redirectRequest(
            int method,
            int nodeNumber,
            Request request,
            long timestamp) throws HttpException, IOException, PoolException, InterruptedException {

        HttpClient client = clusterConnections[nodeNumber];
        Request remoteRequest = client.createRequest(method, request.getURI());

        byte[] body = request.getBody();
        if (body != null) {
            remoteRequest.addHeader(CONTENT_LENGTH_HEADER + body.length);
            remoteRequest.setBody(body);
        }

        remoteRequest.addHeader(TIMESTAMP_HEADER + timestamp);

        return client.invoke(remoteRequest);
    }

    private Response processLocally(int method, String id, Request request, long timestamp) {
        return switch (method) {
            case Request.METHOD_GET -> get(id);
            case Request.METHOD_PUT -> put(id, request.getBody(), timestamp);
            default -> delete(id, timestamp);
        };
    }

    private Response analyzeResponses(int method, List<Response> responses) {
        // The required number of successful responses has already been received here.
        switch (method) {
            case Request.METHOD_GET -> {
                responses.sort(timestampComparator);

                Response response = responses.getFirst();

                return (response.getStatus() == GONE)
                        ? new Response(Response.NOT_FOUND, Response.EMPTY)
                        : response;
            }
            case Request.METHOD_PUT -> {
                return new Response(Response.CREATED, Response.EMPTY);
            }
            default -> { // For delete method.
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
        }
    }

    private Response get(String id) {
        ClusterEntry<MemorySegment> entry = (ClusterEntry<MemorySegment>) dao.get(fromString(id));

        Response response;
        if (entry == null) {
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(TIMESTAMP_HEADER + EMPTY_TIMESTAMP);
        } else {
            response = (entry.value() == null)
                    ? new Response(Response.GONE, Response.EMPTY)
                    : new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
            response.addHeader(TIMESTAMP_HEADER + entry.timestamp());
        }

        return response;
    }

    private Response put(String id, byte[] body, long timestamp) {
        Entry<MemorySegment> entry = new ClusterEntry<>(
                fromString(id),
                MemorySegment.ofArray(body),
                timestamp
        );
        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response delete(String id, long timestamp) {
        Entry<MemorySegment> entry = new ClusterEntry<>(
                fromString(id),
                null,
                timestamp
        );
        dao.upsert(entry);

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
