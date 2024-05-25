package ru.vk.itmo.test.elenakhodosova;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;
import one.nio.util.ByteArrayBuilder;
import one.nio.util.Hash;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.elenakhodosova.dao.BaseEntryWithTimestamp;
import ru.vk.itmo.test.elenakhodosova.dao.EntryWithTimestamp;
import ru.vk.itmo.test.elenakhodosova.dao.ReferenceDao;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> dao;
    private static final String PATH_NAME = "/v0/entity";
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String TIMESTAMP_HEADER = "X-timestamp: ";
    private static final String REDIRECTED_HEADER = "X-redirected";
    public static final String X_TOMB_HEADER = "X-tomb: ";
    private static final char SEPARATOR = '\n';
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EOF = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private final ExecutorService executorService;
    private static final Logger logger = LoggerFactory.getLogger(HttpServerImpl.class);

    private final HttpClient client;
    private final String selfUrl;
    private final List<String> nodes;

    private enum AllowedMethods {
        GET, PUT, DELETE
    }

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao, ExecutorService executorService) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
        this.executorService = executorService;
        this.selfUrl = config.selfUrl();
        this.nodes = config.clusterUrls();
        this.client = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            AllowedMethods method = getMethod(request.getMethod());
            if (method == null) {
                session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
                return;
            }

            switch (request.getPath()) {
                case PATH_NAME -> executeSingleRequest(request, session, method);
                case "/v0/entities" -> executeRangeRequest(request, session);
                default -> sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            }

        } catch (RejectedExecutionException e) {
            logger.error("Request rejected", e);
            session.sendResponse(new Response(TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private void executeSingleRequest(Request request, HttpSession session, AllowedMethods method) {
        executorService.execute(() -> {
            try {
                String fromStr = request.getParameter("from=");
                String ackStr = request.getParameter("ack=");
                int from = fromStr == null || fromStr.isEmpty() ? nodes.size() : Integer.parseInt(fromStr);
                int ack = ackStr == null || ackStr.isEmpty() ? from / 2 + 1 : Integer.parseInt(ackStr);
                if (ack == 0 || from == 0 || ack > from || from > nodes.size()) {
                    sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
                    return;
                }
                String id = request.getParameter("id=");
                if (isParamIncorrect(id)) {
                    session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                    return;
                }
                String isRedirected = request.getHeader(REDIRECTED_HEADER);
                if (isRedirected != null) {
                    sendResponse(session, handleLocalRequest(request, id).get());
                    return;
                }
                processRequest(request, session, id, method, from, ack);
            } catch (Exception e) {
                logger.error("Unexpected error when processing request", e);
                sendError(session, e);
                Thread.currentThread().interrupt();
            }
        });
    }

    private void executeRangeRequest(Request request, HttpSession session) {
        String start = request.getParameter("start=");
        if (isParamIncorrect(start)) {
            sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }
        String end = request.getParameter("end=");

        executorService.execute(() -> {
            try {
                Iterator<EntryWithTimestamp<MemorySegment>> iterator = dao.get(
                        MemorySegment.ofArray(Utf8.toBytes(start)),
                        end == null ? null : MemorySegment.ofArray(Utf8.toBytes(end)));
                byte[] headers = """
                        HTTP/1.1 200 OK\r
                        Transfer-Encoding: chunked\r
                        Connection: close\r
                        \r
                        """.getBytes(StandardCharsets.UTF_8);
                session.write(headers, 0, headers.length);
                iterateOverChunks(session, iterator);
            } catch (IOException e) {
                logger.error("Error while handling range request for keys start={}, end={}", start, end, e);
                sendResponse(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            }
        });
    }

    private void processRequest(Request request, HttpSession session, String id,
                                AllowedMethods method, int from, int ack) {
        List<String> sortedNodes = getSortedNodes(id, from);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger successRequests = new AtomicInteger(0);
        AtomicBoolean requestHandled = new AtomicBoolean(false);
        AtomicInteger lastSuccessResp = new AtomicInteger();
        CompletableFuture<?>[] responses = new CompletableFuture<?>[from];
        AtomicReferenceArray<Response> responseArray = new AtomicReferenceArray<>(from);
        request.addHeader(TIMESTAMP_HEADER + System.currentTimeMillis());
        for (int i = 0; i < from; i++) {
            final int currentIter = i;
            String node = sortedNodes.get(i);
            try {
                responses[currentIter] = routeRequest(node, request, id, method);
                CompletableFuture<?> futureResult = responses[currentIter].whenCompleteAsync((response, throwable) -> {
                    responseArray.set(currentIter, (Response) response);
                    successRequests.incrementAndGet();
                    if (throwable == null) {
                        success.incrementAndGet();
                        lastSuccessResp.set(currentIter);
                    }
                    int lastSuccessCount = success.get();
                    if (validateReplicas(session, lastSuccessCount, successRequests, requestHandled, from, ack)) {
                        return;
                    }

                    if (lastSuccessCount >= ack && requestHandled.compareAndSet(false, true)) {
                        handleSendResponse(request, session, ack, responseArray, lastSuccessResp);
                    }
                });
                validateFuture(futureResult);
            } catch (InterruptedException | IOException e) {
                logger.error("Error during sending request", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private CompletableFuture<Response> routeRequest(String node, Request request, String id, AllowedMethods method)
            throws IOException, InterruptedException {
        if (node.equals(selfUrl)) {
            return handleLocalRequest(request, id);
        }
        return redirectRequest(method, id, node, request);
    }

    private boolean validateReplicas(HttpSession session,
                                     int lastSuccessCount,
                                     AtomicInteger successRequests,
                                     AtomicBoolean requestHandled,
                                     int from,
                                     int ack) {
        if (successRequests.get() == from && lastSuccessCount < ack) {
            if (requestHandled.compareAndSet(false, true)) {
                sendResponse(session, new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY));
            }
            return true;
        }
        return false;
    }

    private void handleSendResponse(Request request,
                                    HttpSession session,
                                    int ack,
                                    AtomicReferenceArray<Response> responseArray,
                                    AtomicInteger lastSuccessResp) {
        if (request.getMethod() == Request.METHOD_GET) {
            sendResponse(session, validateGetRequests(ack, responseArray));
            return;
        }
        Response result = new Response(
                getResponseByCode(responseArray.get(lastSuccessResp.get()).getStatus()),
                responseArray.get(lastSuccessResp.get()).getBody());
        sendResponse(session, result);
    }

    private Response validateGetRequests(int ack, AtomicReferenceArray<Response> responseArray) {
        int notFound = 0;
        long latestTimestamp = 0L;
        Response latestResponse = null;
        for (int j = 0; j < responseArray.length(); j++) {
            Response currentResponse = responseArray.get(j);
            if (currentResponse == null) {
                continue;
            }
            if (currentResponse.getStatus() == HttpURLConnection.HTTP_NOT_FOUND) {
                notFound++;
                continue;
            }
            long timestamp = Long.parseLong(currentResponse.getHeader(TIMESTAMP_HEADER));
            if (timestamp > latestTimestamp) {
                latestTimestamp = timestamp;
                latestResponse = currentResponse;
            }
        }
        if (notFound == ack || latestResponse == null
                || (latestResponse.getBody().length == 0
                && latestResponse.getHeader(X_TOMB_HEADER) != null)
        ) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        } else {
            return new Response(Response.OK, latestResponse.getBody());
        }
    }

    private void sendError(HttpSession session, Exception e) {
        try {
            String responseCode = e.getClass() == HttpException.class ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
            logger.error("Send error", e);
            session.sendError(responseCode, null);
        } catch (Exception ex) {
            logger.error("Unexpected error when sending error", ex);
        }
    }

    private List<String> getSortedNodes(String key, Integer from) {
        Map<Integer, String> nodesHashes = new TreeMap<>();

        for (String node : nodes) {
            nodesHashes.put(Hash.murmur3(node + key), node);
        }
        return nodesHashes.values().stream().limit(from).collect(Collectors.toList());
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    public Response getEntity(String id) {
        try {
            EntryWithTimestamp<MemorySegment> value = dao.get(MemorySegment.ofArray(Utf8.toBytes(id)));
            if (value == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }

            Response response;
            if (value.value() == null) {
                response = new Response(Response.OK, Response.EMPTY);
                response.addHeader("X-tomb: true");
            } else {
                response = Response.ok(value.value().toArray(ValueLayout.JAVA_BYTE));
            }
            response.addHeader(TIMESTAMP_HEADER + value.timestamp());
            return response;
        } catch (Exception ex) {
            logger.error("GET: ", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public Response putEntity(String id, Request request, long timestamp) {
        if (request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        byte[] value = request.getBody();
        try {
            dao.upsert(new BaseEntryWithTimestamp<>(
                    MemorySegment.ofArray(Utf8.toBytes(id)),
                    MemorySegment.ofArray(value),
                    timestamp));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (Exception ex) {
            logger.error("PUT: ", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public Response deleteEntity(String id, long timestamp) {
        try {
            dao.upsert(new BaseEntryWithTimestamp<>(MemorySegment.ofArray(Utf8.toBytes(id)), null, timestamp));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (Exception ex) {
            logger.error("DELETE: ", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private CompletableFuture<Response> redirectRequest(AllowedMethods method,
                                                        String id,
                                                        String clusterUrl,
                                                        Request request) {
        byte[] body = request.getBody();
        HttpRequest redirectedRequest = HttpRequest.newBuilder(URI.create(clusterUrl + PATH_NAME + "?id=" + id))
                .method(
                        method.name(),
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body)
                )
                .header(REDIRECTED_HEADER, "true")
                .header("X-timestamp", request.getHeader(TIMESTAMP_HEADER))
                .build();
        return client.sendAsync(redirectedRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    Response result = new Response(getResponseByCode(response.statusCode()), response.body());
                    Optional<String> respTimestamp = response.headers().firstValue("X-timestamp");
                    respTimestamp.ifPresent(v -> result.addHeader(TIMESTAMP_HEADER + v));
                    Optional<String> headerTomb = response.headers().firstValue("X-tomb");
                    headerTomb.ifPresent(v -> result.addHeader(X_TOMB_HEADER + v));
                    return result;
                });
    }

    private CompletableFuture<Response> handleLocalRequest(Request request, String id) {
        return CompletableFuture.supplyAsync(() -> {
            String timestampHeader = request.getHeader(TIMESTAMP_HEADER);
            long timestamp = timestampHeader == null ? System.currentTimeMillis() : Long.parseLong(timestampHeader);
            switch (request.getMethod()) {
                case Request.METHOD_GET -> {
                    return getEntity(id);
                }
                case Request.METHOD_PUT -> {
                    return putEntity(id, request, timestamp);
                }
                case Request.METHOD_DELETE -> {
                    return deleteEntity(id, timestamp);
                }
                default -> {
                    return new Response(Response.BAD_REQUEST, Response.EMPTY);
                }
            }
        }, executorService);
    }

    private static void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("Error send response", e);
            session.close();
        }
    }

    @Override
    public synchronized void stop() {
        for (SelectorThread selector : selectors) {
            if (selector.selector.isOpen()) {
                for (Session session : selector.selector) {
                    session.close();
                }
            }
        }
        super.stop();
    }

    private boolean isParamIncorrect(String param) {
        return param == null || param.isEmpty();
    }

    private String getResponseByCode(int code) {
        return switch (code) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            case 429 -> TOO_MANY_REQUESTS;
            default -> Response.INTERNAL_ERROR;
        };
    }

    private void validateFuture(CompletableFuture<?> future) {
        if (future == null) {
            logger.warn("Future is null");
        }
    }

    private AllowedMethods getMethod(int method) {
        if (method == 1) return AllowedMethods.GET;
        if (method == 5) return AllowedMethods.PUT;
        if (method == 6) return AllowedMethods.DELETE;
        return null;
    }

    private void iterateOverChunks(HttpSession session, Iterator<EntryWithTimestamp<MemorySegment>> iterator) {
        try {
            boolean callsCounter = true;
            while (callsCounter) {
               callsCounter = processChunk(session, iterator);
            }
        } catch (IOException e) {
            logger.error("Error during iteration over chunks", e);
            sendResponse(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    private boolean processChunk(Session session, Iterator<EntryWithTimestamp<MemorySegment>> iterator)
            throws IOException {
        if (iterator.hasNext()) {
            EntryWithTimestamp<MemorySegment> entry = iterator.next();
            byte[] keyBytes = entry.key().toArray(ValueLayout.JAVA_BYTE);
            byte[] valueBytes = entry.value().toArray(ValueLayout.JAVA_BYTE);
            int length = keyBytes.length + valueBytes.length + 1;
            ByteArrayBuilder bytesBuilder = new ByteArrayBuilder();
            bytesBuilder
                    .append(Utf8.toBytes(Integer.toHexString(length)))
                    .append(CRLF)
                    .append(keyBytes)
                    .append(SEPARATOR)
                    .append(valueBytes)
                    .append(CRLF);
            byte[] bytes = bytesBuilder.toBytes();
            session.write(bytes, 0, bytes.length);
            return true;
        } else {
            session.write(EOF, 0, EOF.length);
            session.scheduleClose();
            return false;
        }
    }
}
