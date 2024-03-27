package ru.vk.itmo.test.osokindm;

import one.nio.http.HttpServerConfig;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.osokindm.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.lang.Math.floor;

public class ServiceImpl implements Service {

    private static final int MEMORY_LIMIT_BYTES = 8 * 1024;
    private static final int CONNECTION_TIMEOUT_MS = 250;
    private static final String DEFAULT_PATH = "/v0/entity";
    private static final String TIMESTAMP_HEADER = "Request-timestamp";
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ServiceConfig config;
    private RendezvousRouter router;
    private DaoWrapper daoWrapper;
    private HttpServerImpl server;
    private final HttpClient client;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.of(CONNECTION_TIMEOUT_MS, ChronoUnit.MILLIS))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            daoWrapper = new DaoWrapper(new Config(config.workingDir(), MEMORY_LIMIT_BYTES));
            router = new RendezvousRouter(config.clusterUrls());
            server = new HttpServerImpl(createServerConfig(config.selfPort()));
            server.addRequestHandlers(this);
            server.start();
            LOGGER.debug("Server started: " + config.selfUrl());
        } catch (IOException e) {
            LOGGER.error("Error occurred while starting the server");
            throw new IOException(e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        server.stop();
        try {
            daoWrapper.stop();
        } catch (IOException e) {
            LOGGER.error("Error occurred while closing database");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Path(DEFAULT_PATH)
    public Response entity(Request request,
                           @Param(value = "id", required = true) String id,
                           @Param(value = "ack") Integer ack,
                           @Param(value = "from") Integer from) throws TimeoutException {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, "Invalid id".getBytes(StandardCharsets.UTF_8));
        }

        LOGGER.info("got request in entity:  " + request.toString());
        if (request.getHeader(TIMESTAMP_HEADER) != null) {
            String timestamp = request.getHeader(TIMESTAMP_HEADER + ": ");
            try {
                if (timestamp != null && !timestamp.isBlank()) {
                    long parsedTimestamp = Long.parseLong(timestamp);
                    return handleRequestLocally(request, id, parsedTimestamp);
                }
            } catch (NumberFormatException e) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
        }

        if (from == null) {
            from = config.clusterUrls().size();
        }
        if (ack == null) {
            ack = calculateAck(from);
        }
        if (ack > from) {
            return new Response(Response.BAD_REQUEST, "'ack' is greater than 'from'".getBytes(StandardCharsets.UTF_8));
        }

        List<Node> targetNodes = router.getNodes(id, from);
        List<Response> responses = sendRequestsToNodes(request, targetNodes, id);
        if (responses.size() < ack) {
            return new Response(Response.GATEWAY_TIMEOUT, "Not enough replicas responded".getBytes(StandardCharsets.UTF_8));
        } else if (request.getMethod() == Request.METHOD_GET) {
            return selectLatestResponse(responses);
        } else {
            return responses.getFirst();
        }
    }

    private List<Response> sendRequestsToNodes(Request request, List<Node> nodes, String id) {
        List<Response> responses = new ArrayList<>();
        for (Node node : nodes) {
            if (!node.isAlive()) {
                continue;
                // todo handle unavailable
                // не добавляем неуспешные запросы
//                responses.add(new Response(Response.SERVICE_UNAVAILABLE, "Node is unavailable".getBytes(StandardCharsets.UTF_8)));
            }
            // проверить, переправляли ли запрос, те, указан ли уже timestamp в хедере
            try {
                long timestamp = getTimestamp(request);
                if (node.address.equals(config.selfUrl())) {
                    responses.add(handleRequestLocally(request, id, timestamp));
                } else {
                    responses.add(forwardRequestToNode(request, node, timestamp));
                }
            } catch (NumberFormatException e) {
                responses.add(new Response(Response.BAD_REQUEST, Response.EMPTY));
            }
        }
        return responses;
    }

    private long getTimestamp(Request request) throws NumberFormatException {
        // check if the request has been forwarded, so we can use given timestamp
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (timestamp != null && !timestamp.isBlank()) {
            return Long.parseLong(timestamp);
        }
        return System.currentTimeMillis();
    }

    private Response selectLatestResponse(List<Response> responses) {
        Response latestResponse = responses.getFirst();
        long latestTimestamp = Long.MIN_VALUE;

        for (Response response : responses) {
            if (response.getStatus() != HttpURLConnection.HTTP_NOT_FOUND) {
                long timestamp = extractTimestampFromResponse(response);

                if (timestamp > latestTimestamp) {
                    latestTimestamp = timestamp;
                    latestResponse = response;
                }
            }
        }

        return latestResponse;
    }

    private long extractTimestampFromResponse(Response response) {
        String timestamp = response.getHeaders()[2];
        String timestampValue = timestamp.substring(TIMESTAMP_HEADER.length() + 2).trim();
        return Long.parseLong(timestampValue);
    }

    private Response forwardRequestToNode(Request request, Node node, long timestamp) {
        try {
            return makeProxyRequest(request, node.address, timestamp);
        } catch (TimeoutException e) {
            node.captureError();
            LOGGER.error(node + " not responding", e);
            return new Response(Response.REQUEST_TIMEOUT, Response.EMPTY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (ExecutionException | IOException e) {
            LOGGER.error(node + " not responding", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response handleRequestLocally(Request request, String id, long timestamp) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> result = daoWrapper.get(id);
                if (result == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                }

                if (result.value() == null) {
                    Response response = new Response(Response.NOT_FOUND, longToBytes(result.timestamp()));
                    response.addHeader(TIMESTAMP_HEADER + ": " + result.timestamp());
                    return response;
                }
                Response response = Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
                response.addHeader(TIMESTAMP_HEADER + ": " + result.timestamp());
                return response;
            }
            case Request.METHOD_PUT -> {
                daoWrapper.upsert(id, request, timestamp);
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                daoWrapper.delete(id, timestamp);
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private Response makeProxyRequest(Request request, String nodeAddress, long timestamp)
            throws ExecutionException, InterruptedException, TimeoutException, IOException {
        byte[] body = request.getBody();
        if (body == null) {
            body = Response.EMPTY;
        }
        HttpRequest proxyRequest = HttpRequest
                .newBuilder(URI.create(nodeAddress + request.getURI()))
                .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                .method(request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        LOGGER.info("about to send proxy request  " );

        return client
                .sendAsync(proxyRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(ServiceImpl::processedResponse)
                .get(5000, TimeUnit.MILLISECONDS);
        // todo handleAsync and lower timeout
    }

    private static Response processedResponse(HttpResponse<byte[]> response) {
        long timestamp = response.headers().map().containsKey("request-timestamp")
                ? Long.parseLong(response.headers().map().get("request-timestamp").getFirst())
                : -1;

        if (response.body().length == 0  && timestamp == -1) {
            return new Response(String.valueOf(response.statusCode()), Response.EMPTY);
        }

        Response response1 = new Response(String.valueOf(response.statusCode()), response.body());
        LOGGER.info("got response in processedResponse:  " + response1);

        response1.addHeader(TIMESTAMP_HEADER + ": " + timestamp);
        return response1;
    }

    private static HttpServerConfig createServerConfig(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static int calculateAck(int clusterSize) {
        if (clusterSize <= 2) {
            return clusterSize;
        }
        return (int) floor(clusterSize * 0.75);
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte) (l & 0xFF);
            l >>= Byte.SIZE;
        }
        return result;
    }

    public static long bytesToLong(final byte[] b) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result <<= Byte.SIZE;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }

}
