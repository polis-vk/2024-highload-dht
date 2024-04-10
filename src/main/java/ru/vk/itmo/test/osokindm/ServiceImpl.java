package ru.vk.itmo.test.osokindm;

import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Math.floor;

public class ServiceImpl implements Service {

    private static final int MEMORY_LIMIT_BYTES = 8 * 1024;
    private static final int CONNECTION_TIMEOUT_MS = 250;
    private static final String DEFAULT_PATH = "/v0/entity";
    private static final String TIMESTAMP_HEADER = "Request-timestamp";
    private static final String TIMESTAMP_HEADER_LC = "request-timestamp";
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ServiceConfig config;
    private final HttpClient client;
    private final AtomicInteger successes;
    private final AtomicInteger failures;
    private final AtomicReference<Response> latestResponse;
    private RendezvousRouter router;
    private DaoWrapper daoWrapper;
    private HttpServerImpl server;
    private long responseTime = 0;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.of(CONNECTION_TIMEOUT_MS, ChronoUnit.MILLIS))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        successes = new AtomicInteger();
        failures = new AtomicInteger();
        latestResponse = new AtomicReference<>();
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
    public void entity(Request request, HttpSession session,
                       @Param(value = "id", required = true) String id,
                       @Param(value = "ack") Integer ack,
                       @Param(value = "from") Integer from) throws TimeoutException, IOException {
        if (id == null || id.isBlank()) {
            session.sendResponse(new Response(Response.BAD_REQUEST, "Invalid id".getBytes(StandardCharsets.UTF_8)));
            return;
        }

        LOGGER.info("got request in entity:  " + request.toString());
        if (request.getHeader(TIMESTAMP_HEADER) != null) {
            String timestamp = request.getHeader(TIMESTAMP_HEADER + ": ");
            try {
                if (timestamp != null && !timestamp.isBlank()) {
                    long parsedTimestamp = Long.parseLong(timestamp);
                    session.sendResponse(handleRequestLocally(request, id, parsedTimestamp));
                    return;
                }
            } catch (NumberFormatException e) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
        }

        if (from == null) {
            from = config.clusterUrls().size();
        }

        if (ack == null) {
            ack = calculateAck(from);
        }

        if (ack > from || ack == 0) {
            session.sendResponse(new Response(Response.BAD_REQUEST, "wrong 'ack' value".getBytes(StandardCharsets.UTF_8)));
            return;
        }

        List<Node> targetNodes = router.getNodes(id, from);
        sendRequestsToNodes(request, session, targetNodes, id, ack, from);
    }
    private void sendRequestsToNodes(Request request, HttpSession session, List<Node> nodes, String id, int ack, int from) {
        for (Node node : nodes) {
            if (!node.isAlive()) {
                LOGGER.info("node is unreachable: " + node.address);
                failures.incrementAndGet();
                continue;
            }
            try {
                long timestamp = getTimestamp(request);
                CompletableFuture<Response> futureResponse;
                if (node.address.equals(config.selfUrl())) {
                    futureResponse = CompletableFuture.supplyAsync(()->handleRequestLocally(request, id, timestamp));
                } else {
                    futureResponse = forwardRequestToNode(request, node, timestamp);
                }
                futureResponse
                        .whenCompleteAsync((resp, ex) -> {
                            if (resp != null) {
                                updateLatestResponse(resp, request.getMethod());
                                if (responseIsGood(resp)) {
                                    if (successes.incrementAndGet() >= ack) {
                                        try {
                                            session.sendResponse(latestResponse.get());
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                } else {
                                    if (failures.incrementAndGet() > from - ack) {
                                        String message = "Not enough replicas responded";
                                        LOGGER.info("Not enough replicas responded");
                                        try {
                                            session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, message.getBytes(StandardCharsets.UTF_8)));
                                        } catch (IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                }
                            }
                        })
                        .exceptionally(ex -> {
                            failures.incrementAndGet();
                            LOGGER.error("NIGGA " + ex.toString());
                            try {
                                session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return null;
                        });
            } catch (NumberFormatException e) {
                LOGGER.error(e.toString());
            }
        }
    }

    private boolean responseIsGood(Response response) {
        return response.getStatus() <= HttpURLConnection.HTTP_PARTIAL
                || response.getStatus() == HttpURLConnection.HTTP_NOT_FOUND;
    }

    private long getTimestamp(Request request) throws NumberFormatException {
        // check if the request has been forwarded, so we can use given timestamp
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        if (timestamp != null && !timestamp.isBlank()) {
            return Long.parseLong(timestamp);
        }
        return System.currentTimeMillis();
    }

    private void updateLatestResponse(Response response, int method) {
        if (method != Request.METHOD_GET) {
            latestResponse.set(response);
            return;
        }
        long timestamp = extractTimestampFromResponse(response);
        if (timestamp > responseTime) {
            responseTime = timestamp;
            latestResponse.set(response);
        }
    }

    private long extractTimestampFromResponse(Response response) {
        String timestamp = response.getHeaders()[2];
        if (timestamp == null) {
            return -1;
        }
        String timestampValue = timestamp.substring(TIMESTAMP_HEADER.length() + 2).trim();
        return Long.parseLong(timestampValue);
    }

    private CompletableFuture<Response> forwardRequestToNode(Request request, Node node, long timestamp) {
        try {
            return makeProxyRequest(request, node.address, timestamp);
        } catch (TimeoutException e) {
            node.captureError();
            LOGGER.error(node + " not responding", e);
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
//            return null;
            return CompletableFuture.completedFuture(null);

        } catch (ExecutionException | IOException e) {
            LOGGER.error(node + " not responding", e);
//            return null;
            return CompletableFuture.completedFuture(null);

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

    private CompletableFuture<Response> makeProxyRequest(Request request, String nodeAddress, long timestamp)
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
         LOGGER.info("checking request " + proxyRequest.headers().toString());

        return client
                .sendAsync(proxyRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(ServiceImpl::processedResponse);
    }

    private static Response processedResponse(HttpResponse<byte[]> response) {
        long timestamp = response.headers().map().containsKey(TIMESTAMP_HEADER_LC)
                ? Long.parseLong(response.headers().map().get(TIMESTAMP_HEADER_LC).getFirst())
                : -1;

        LOGGER.info("got smth " + timestamp);
        if (response.body().length == 0 && timestamp == -1) {
            return new Response(String.valueOf(response.statusCode()), Response.EMPTY);
        }
        Response processedResponse = new Response(String.valueOf(response.statusCode()), response.body());
        processedResponse.addHeader(TIMESTAMP_HEADER + ": " + timestamp);
        return processedResponse;
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

    @ServiceFactory(stage = 5)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }

}
