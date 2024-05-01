package ru.vk.itmo.test.chebotinalexandr;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.chebotinalexandr.dao.Dao;
import ru.vk.itmo.test.chebotinalexandr.dao.entry.Entry;
import ru.vk.itmo.test.chebotinalexandr.dao.entry.TimestampEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class StorageServer extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(StorageServer.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executor;
    private final List<String> clusterUrls;
    private final String selfUrl;
    private final HttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String TIMESTAMP_HEADER = "X-Timestamp";
    private static final String INTERNAL_HEADER = "X-Internal";
    private static final Set<Integer> allowedMethods = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );

    public StorageServer(
            ServiceConfig config,
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ExecutorService executor,
            HttpClient httpClient
    ) throws IOException {
        super(createConfig(config));
        this.dao = dao;
        this.executor = executor;
        this.clusterUrls = config.clusterUrls();
        this.selfUrl = config.selfUrl();
        this.httpClient = httpClient;
    }

    private static HttpServerConfig createConfig(ServiceConfig config) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = config.selfPort();

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        if (closed.getAndSet(true)) {
            return;
        }
        super.stop();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (!allowedMethods.contains(request.getMethod())) {
            sendEmptyBodyResponse(Response.METHOD_NOT_ALLOWED, session);
            return;
        }

        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            sendEmptyBodyResponse(Response.BAD_REQUEST, session);
            return;
        }

        //check for internal request
        if (request.getHeader(INTERNAL_HEADER) != null) {
            long timestamp = parseTimestamp(request.getHeader(TIMESTAMP_HEADER + ": "));
            Response response = handleRequest(request, id, timestamp);
            session.sendResponse(response);
            return;
        }

        String ackParameter = request.getParameter("ack=");
        String fromParameter = request.getParameter("from=");

        int from = fromParameter == null || fromParameter.isBlank()
                ? clusterUrls.size() : Integer.parseInt(fromParameter);
        int ack = ackParameter == null || ackParameter.isBlank()
                ? quorum(from) : Integer.parseInt(ackParameter);

        if (ack > from || ack == 0) {
            sendEmptyBodyResponse(Response.BAD_REQUEST, session);
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    int partition = selectPartition(id);
                    pickResponses(request, session, id, ack, from, partition);
                } catch (IOException e) {
                    log.error("Exception during handleRequest: ", e);
                    sendEmptyBodyResponse(Response.INTERNAL_ERROR, session);
                    session.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Request rejected", e);
            sendEmptyBodyResponse(Response.SERVICE_UNAVAILABLE, session);
        }
    }

    private void pickResponses(
            Request request,
            HttpSession session,
            String id,
            int ack,
            int from,
            int partition
    ) throws IOException, InterruptedException {
        long timestamp = System.currentTimeMillis();
        List<Response> responses = new ArrayList<>();
        int httpMethod = request.getMethod();

        for (int i = 0; i < from; i++) {
            int nodeIndex = (partition + i) % clusterUrls.size();

            Response response;

            if (isCurrentPartition(nodeIndex)) {
                response = handleRequest(request, id, timestamp);
            } else {
                response = remote(request, timestamp, nodeIndex);
            }

            if (response != null) {
                responses.add(response);
            }

            boolean enough = compareReplicasResponses(httpMethod, session, responses, ack);
            if (enough) {
                return;
            }
        }

        if (responses.size() < ack) {
            sendEmptyBodyResponse(NOT_ENOUGH_REPLICAS, session);
        }
    }

    private boolean compareReplicasResponses(
            int httpMethod,
            HttpSession session,
            List<Response> responses,
            int ack
    ) throws IOException {
        boolean enough = responses.size() >= ack;

        if (enough) {
            if (httpMethod == Request.METHOD_PUT || httpMethod == Request.METHOD_DELETE) {
                session.sendResponse(responses.getFirst());
                return false;
            } else {
                session.sendResponse(findLastWriteResponse(responses));
                return true;
            }
        }

        return false;
    }

    private Response findLastWriteResponse(List<Response> responses) {
        Response result = responses.getFirst();
        long maxTimestamp = 0;
        for (Response response : responses) {
            String timestampHeader = response.getHeader(TIMESTAMP_HEADER + ": ");

            if (timestampHeader != null) {
                long timestamp = parseTimestamp(timestampHeader);
                if (maxTimestamp < timestamp) {
                    maxTimestamp = timestamp;
                    result = response;
                }
            }
        }

        return result;
    }

    private long parseTimestamp(String timestampHeader) {
        return Long.parseLong(timestampHeader);
    }

    private Response remote(
            Request request,
            long timestamp,
            int nodeIndex
    ) throws IOException, InterruptedException {
        Response response = null;
        try {
            Response responseFromNode = routeRequest(nodeIndex, request, timestamp);
            if ((responseFromNode.getStatus() == 201)
                    || (responseFromNode.getStatus() == 200)
                    || (responseFromNode.getStatus() == 404)
                    || (responseFromNode.getStatus() == 202)) {
                response = responseFromNode;
            }
        } catch (ConnectException e) {
            log.info("Can't connect to node " + nodeIndex);
        }

        return response;
    }

    private int quorum(int from) {
        return from / 2 + 1;
    }

    private Response routeRequest(
            int partition,
            Request request,
            long timestamp
    ) throws IOException, InterruptedException {
        String partitionUrl = getPartitionUrl(partition) + request.getURI();

        HttpRequest newRequest = HttpRequest.newBuilder()
                .uri(URI.create(partitionUrl))
                .header(INTERNAL_HEADER, "true")
                .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                .method(
                        request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(
                                request.getBody() == null ? Response.EMPTY : request.getBody()
                        )
                )
                .build();

        HttpResponse<byte[]> response = httpClient.send(newRequest, HttpResponse.BodyHandlers.ofByteArray());
        return extractResponseFromNode(response);
    }

    private Response extractResponseFromNode(HttpResponse<byte[]> response) {
        String responseCode = switch (response.statusCode()) {
            case HttpURLConnection.HTTP_OK -> Response.OK;
            case HttpURLConnection.HTTP_CREATED -> Response.CREATED;
            case HttpURLConnection.HTTP_ACCEPTED -> Response.ACCEPTED;
            case HttpURLConnection.HTTP_BAD_REQUEST -> Response.BAD_REQUEST;
            case HttpURLConnection.HTTP_NOT_FOUND -> Response.NOT_FOUND;
            case HttpURLConnection.HTTP_INTERNAL_ERROR -> Response.INTERNAL_ERROR;
            default -> throw new IllegalStateException("Can not define response code:" + response.statusCode());
        };

        Response converted = new Response(responseCode, response.body());
        response
                .headers()
                .firstValue(TIMESTAMP_HEADER)
                .ifPresent(
                        v -> converted.addHeader(TIMESTAMP_HEADER + ": " + v)
                );

        return converted;
    }

    private boolean isCurrentPartition(int partitionNumber) {
        return clusterUrls.get(partitionNumber).equals(selfUrl);
    }

    private int selectPartition(String id) {
        long maxHash = Long.MIN_VALUE;
        int partition = -1;

        for (int i = 0; i < clusterUrls.size(); i++) {
            String url = clusterUrls.get(i);
            long nodeHash = Hash.murmur3(url + id);
            if (nodeHash > maxHash) {
                maxHash = nodeHash;
                partition = i;
            }
        }

        return partition;
    }

    private String getPartitionUrl(int partition) {
        return clusterUrls.get(partition);
    }

    private Response handleRequest(Request request, String id, long timestamp) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> entry = dao.get(fromString(id));

                if (entry == null) {
                    //not a tombstone
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                } else if (entry.value() == null) {
                    //tombstone
                    Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                    response.addHeader(TIMESTAMP_HEADER + ": " + entry.timestamp());
                    return response;
                } else {
                    Response response = Response.ok(toBytes(entry.value()));
                    response.addHeader(TIMESTAMP_HEADER + ": " + entry.timestamp());
                    return response;
                }
            }
            case Request.METHOD_PUT -> {
                Entry<MemorySegment> entry = new TimestampEntry<>(
                        fromString(id),
                        fromBytes(request.getBody()),
                        timestamp
                );
                dao.upsert(entry);

                Response response = new Response(Response.CREATED, Response.EMPTY);
                response.addHeader(TIMESTAMP_HEADER + ": " + entry.timestamp());
                return response;
            }
            case Request.METHOD_DELETE -> {
                //tombstone
                Entry<MemorySegment> entry = new TimestampEntry<>(
                        fromString(id),
                        null,
                        timestamp
                );
                dao.upsert(entry);

                Response response = new Response(Response.ACCEPTED, Response.EMPTY);
                response.addHeader(TIMESTAMP_HEADER + ": " + entry.timestamp());
                return response;
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private static void sendEmptyBodyResponse(String responseCode, HttpSession session) {
        Response emptyBodyResponse = new Response(responseCode, Response.EMPTY);
        try {
            session.sendResponse(emptyBodyResponse);
        } catch (IOException e) {
            log.error("Exception during send empty response ", e);
        }
    }

    private static MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment fromBytes(byte[] bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private static byte[] toBytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }
}

