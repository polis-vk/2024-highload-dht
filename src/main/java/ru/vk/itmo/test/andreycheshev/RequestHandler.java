package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpException;
import one.nio.http.HttpSession;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class RequestHandler implements Sender {
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

    public static final String TIMESTAMP_HEADER = "X-Timestamp";

    private static final int EMPTY_TIMESTAMP = -1;

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final RendezvousDistributor distributor;

    private final Executor remoteCallExecutor = Executors.newFixedThreadPool(6);
    private final Executor senderExecutor = Executors.newFixedThreadPool(6);
    private final Executor localExecutor = Executors.newFixedThreadPool(6);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .executor(remoteCallExecutor)
            .connectTimeout(Duration.ofMillis(400))
            .version(HttpClient.Version.HTTP_1_1)
            .build();


    public RequestHandler(
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            RendezvousDistributor distributor) {

        this.dao = dao;
        this.distributor = distributor;
    }

    public void handle(Request request, HttpSession session) throws InterruptedException, IOException {

        // Checking the correctness.
        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            HttpUtils.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
            return;
        }

        String id = request.getParameter(ID_PARAMETER);
        if (id == null || id.isEmpty()) {
            HttpUtils.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
            return;
        }

        int method = request.getMethod();
        if (!AVAILABLE_METHODS.contains(method)) {
            HttpUtils.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY), session);
            return;
        }

        // A timestamp is an indication that the request
        // came from the client directly or from the cluster node.
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (timestamp != null) {
            // The request came from a remote node.
            try {
                processLocallyToSend(method, id, request, Long.parseLong(timestamp), session);
            } catch (NumberFormatException e) {
                HttpUtils.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
                return;
            }
        }

        // Get and check "ack" and "from" parameters.
        ParametersParser parser = new ParametersParser(distributor);
        try {
            parser.parseAckFrom(
                    request.getParameter(ACK_PARAMETER),
                    request.getParameter(FROM_PARAMETER)
            );
        } catch (IllegalArgumentException e) {
            HttpUtils.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
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
                ? EMPTY_TIMESTAMP
                : System.currentTimeMillis();

        ResponseCollector collector = new ResponseCollector(session, method, ack, from);

        for (int nodeIndex : nodesIndices) {
            String node = distributor.getNodeUrlByIndex(nodeIndex);
            try {
                if (distributor.isOurNode(nodeIndex)) {
                    processLocally(method, id, request, timestamp, collector);
                } else {
                    processRemotely(node, request, timestamp, collector);
                }
            } catch (SocketTimeoutException e) {
                LOGGER.error("Request processing time exceeded on another node", e);
            } catch (PoolException | HttpException | IOException e) {
                LOGGER.error("An error occurred when processing a request on another node", e);
            }
        }

        collector.trySendResponse();
    }

    private void processLocallyToSend(
            int method,
            String id,
            Request request,
            long timestamp,
            HttpSession session) {

        getLocalFuture(method, id, request, timestamp)
                .thenAcceptAsync(
                        elements -> HttpUtils.sendResponse(
                                HttpUtils.getOneNioResponse(method, elements),
                                session
                        ),
                        senderExecutor
                );
    }


    private void processLocally(
            int method,
            String id,
            Request request,
            long timestamp,
            ResponseCollector collector
    ) {
        getLocalFuture(method, id, request, timestamp)
                .thenApplyAsync(collector::add,
                        remoteCallExecutor
                ).thenAcceptAsync(
                        condition -> {
                            if (condition) {
                                collector.trySendResponse();
                            }
                        },
                        senderExecutor
                );
    }

    private CompletableFuture<ResponseElements> getLocalFuture(
            int method,
            String id,
            Request request,
            long timestamp) {
        return CompletableFuture
                .supplyAsync(
                        () -> switch (method) {
                            case Request.METHOD_GET -> get(id);
                            case Request.METHOD_PUT -> put(id, request.getBody(), timestamp);
                            default -> delete(id, timestamp);
                        },
                        localExecutor
                );
    }

    private void processRemotely(
            String node,
            Request request,
            long timestamp,
            ResponseCollector collector)
            throws HttpException, IOException, PoolException {

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(node + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                .timeout(Duration.ofMillis(500))
                .build();

        httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(
                        httpResponse -> { // java.net response
                            Optional<String> optTimestamp = httpResponse.headers().firstValue(TIMESTAMP_HEADER);
                            long responseTimestamp = optTimestamp.isPresent()
                                    ? Long.parseLong(optTimestamp.get())
                                    : EMPTY_TIMESTAMP;

                            return collector.add(
                                    new ResponseElements(
                                            httpResponse.statusCode(),
                                            httpResponse.body(),
                                            responseTimestamp
                                    )
                            );
                        },
                        remoteCallExecutor
                ).thenAcceptAsync(
                        condition -> {
                            if (condition) {
                                collector.trySendResponse();
                            }
                        },
                        senderExecutor
                );
    }

    private ResponseElements get(String id) {
        ClusterEntry<MemorySegment> entry = (ClusterEntry<MemorySegment>) dao.get(fromString(id));

        ResponseElements response;
        if (entry == null) {
            response = new ResponseElements(404, Response.EMPTY, -1);
        } else {
            long timestamp = entry.timestamp();
            response = (entry.value() == null)
                    ? new ResponseElements(410, Response.EMPTY, timestamp)
                    : new ResponseElements(200, entry.value().toArray(ValueLayout.JAVA_BYTE), timestamp);
        }

        return response;
    }

    private ResponseElements put(String id, byte[] body, long timestamp) {
        Entry<MemorySegment> entry = new ClusterEntry<>(
                fromString(id),
                MemorySegment.ofArray(body),
                timestamp
        );
        dao.upsert(entry);

        return new ResponseElements(201, Response.EMPTY, -1);
    }

    private ResponseElements delete(String id, long timestamp) {
        Entry<MemorySegment> entry = new ClusterEntry<>(
                fromString(id),
                null,
                timestamp
        );
        dao.upsert(entry);

        return new ResponseElements(202, Response.EMPTY, -1);
    }

    private static MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void send() {

    }
}
