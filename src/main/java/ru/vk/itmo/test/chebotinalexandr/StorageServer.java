package ru.vk.itmo.test.chebotinalexandr;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.dao.TimestampEntry;
import ru.vk.itmo.test.chebotinalexandr.dao.MurmurHash;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Time;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class StorageServer extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(StorageServer.class);
    private static final String PATH = "/v0/entity";
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executor;
    private final List<String> clusterUrls;
    private final String selfUrl;
    private final HttpClient httpClient;
    private AtomicBoolean closed = new AtomicBoolean();

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
    public void handleRequest(Request request, HttpSession session) {
        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            sendEmptyBodyResponse(Response.BAD_REQUEST, session);
            return;
        }

        try {
            executor.execute(() -> {
                try {
                    int partition = selectPartition(id);

                    if (isCurrentPartition(partition)) {
                        super.handleRequest(request, session);
                    } else {
                        routeRequest(partition, request, session);
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

    private void routeRequest(
            int partition,
            Request request,
            HttpSession session
    ) throws IOException, InterruptedException {
        String partitionUrl = getPartitionUrl(partition) + request.getURI();

        HttpRequest newRequest = HttpRequest.newBuilder()
                .uri(URI.create(partitionUrl))
                .method(
                        request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(
                                request.getBody() == null ? Response.EMPTY : request.getBody()
                        )
                )
                .build();

        HttpResponse<byte[]> response = httpClient.send(newRequest, HttpResponse.BodyHandlers.ofByteArray());
        session.sendResponse(extractResponseFromNode(response));
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

        return new Response(responseCode, response.body());
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

    @Path(PATH)
    public Response entity(Request request, @Param("id") String id) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> entry = dao.get(fromString(id));

                if (entry == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                } else {
                    return Response.ok(toBytes(entry.value()));
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

