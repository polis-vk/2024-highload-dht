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
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.dao.TimestampEntry;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StorageServer extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(StorageServer.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executor;
    private final List<String> clusterUrls;
    private final String selfUrl;
    private final HttpClient httpClient;
    private AtomicBoolean closed = new AtomicBoolean();
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";

    public StorageServer(
            ServiceConfig config,
            Dao<MemorySegment, Entry<MemorySegment>> dao, ExecutorService executor
    ) throws IOException {
        super(createConfig(config));
        this.dao = dao;
        this.executor = executor;
        this.clusterUrls = config.clusterUrls();
        this.selfUrl = config.selfUrl();
        this.httpClient = HttpClient.newHttpClient();
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
        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            sendEmptyBodyResponse(Response.BAD_REQUEST, session);
            return;
        }

        //check for internal request
        if (request.getHeader("internal") != null) {
            Response response = handleRequest(request, id);
            session.sendResponse(response);
            return;
        }

        String ackParameter = request.getParameter("ack=");
        String fromParameter = request.getParameter("from=");

        int from, ack;
        if (fromParameter == null || fromParameter.isBlank()) {
            from = clusterUrls.size();
        } else {
            from = Integer.parseInt(fromParameter);
        }

        if (ackParameter == null || ackParameter.isBlank()) {
            ack = quorum(from);
        } else {
            ack = Integer.parseInt(ackParameter);
        }

        if (ack > from || ack == 0) {
            sendEmptyBodyResponse(Response.BAD_REQUEST, session);
        }

        try {
            executor.execute(() -> {
                try {
                    List<Response> responses = new ArrayList<>();
                    int partition = selectPartition(id);

                    for (int i = 0; i < from; i++) {
                        int nodeIndex = (partition + i) % clusterUrls.size();
                        if (isCurrentPartition(nodeIndex)) {
                            Response response = handleRequest(request, id);
                            responses.add(response);
                        } else {
                            try {
                                Response response = routeRequest(nodeIndex, request);
                                if ((response.getStatus() == 201)
                                        || (response.getStatus() == 200)
                                        || (response.getStatus() == 404)
                                        || (response.getStatus() == 202)) {
                                    responses.add(response);
                                }
                            } catch (ConnectException e) {
                                log.error("Node is unavailable");
                            }
                        }
                    }

                    if (responses.size() >= ack) {
                        if (request.getMethod() == Request.METHOD_PUT || request.getMethod() == Request.METHOD_DELETE) {
                            session.sendResponse(responses.getFirst());
                        } else {
                            Response lastWriteResponse = responses.getFirst();

                            long maxTimestamp = 0;

                            for (Response response : responses) {
                                String timestampHeader = response.getHeaders()[response.getHeaderCount() - 1];
                                Pattern pattern = Pattern.compile("\\d+");
                                Matcher matcher = pattern.matcher(timestampHeader);

                                long timestamp = 0;
                                if (matcher.find()) {
                                    timestamp = Long.parseLong(matcher.group());
                                }

                                if (maxTimestamp < timestamp) {
                                    maxTimestamp = timestamp;
                                    lastWriteResponse = response;
                                }
                            }

                            session.sendResponse(lastWriteResponse);
                        }
                    } else {
                        sendEmptyBodyResponse(NOT_ENOUGH_REPLICAS, session);
                    }

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

    private int quorum(int from) {
        return from / 2 + 1;
    }

    private Response routeRequest(int partition, Request request) throws IOException, InterruptedException {
        String partitionUrl = getPartitionUrl(partition) + request.getURI();

        HttpRequest newRequest = HttpRequest.newBuilder()
                .uri(URI.create(partitionUrl))
                .header("internal", "true")
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
        converted.addHeader(response.headers().firstValue("X-Timestamp").orElse(""));

        return converted;
    }

    private boolean isCurrentPartition(int partitionNumber) {
        return clusterUrls.get(partitionNumber).equals(selfUrl);
    }

    private int selectPartition(String id) {
        Long maxHash = Long.MIN_VALUE;
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

    private Response handleRequest(Request request, String id) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> entry = dao.get(fromString(id));

                if (entry == null) {
                    //not a tombstone
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                } else if (entry.value() == null) {
                    //tombstone
                    Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                    response.addHeader("X-Timestamp: " + entry.timestamp());
                    return response;
                } else {
                    Response response = Response.ok(toBytes(entry.value()));
                    response.addHeader("X-Timestamp: " + entry.timestamp());
                    return response;
                }
            }
            case Request.METHOD_PUT -> {
                Entry<MemorySegment> entry = new TimestampEntry<>(
                        fromString(id),
                        fromBytes(request.getBody()),
                        System.currentTimeMillis()
                );
                dao.upsert(entry);

                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                Entry<MemorySegment> entry = new TimestampEntry<>(
                        fromString(id),
                        null,
                        System.currentTimeMillis()
                );
                dao.upsert(entry);

                return new Response(Response.ACCEPTED, Response.EMPTY);
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

