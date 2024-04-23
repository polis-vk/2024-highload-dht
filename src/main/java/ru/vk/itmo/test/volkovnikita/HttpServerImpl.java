package ru.vk.itmo.test.volkovnikita;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import one.nio.server.SelectorThread;
import one.nio.util.Hash;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.volkovnikita.dao.EntryWithTimestamp;
import ru.vk.itmo.test.volkovnikita.dao.ReferenceDao;
import ru.vk.itmo.test.volkovnikita.dao.TimestampEntry;
import ru.vk.itmo.test.volkovnikita.util.ChunkHttpResponse;
import ru.vk.itmo.test.volkovnikita.util.CustomSession;
import ru.vk.itmo.test.volkovnikita.util.NotEnoughReplicasException;

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
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static ru.vk.itmo.test.volkovnikita.util.CustomHttpStatus.NOT_ENOUGH_REPLICAS;
import static ru.vk.itmo.test.volkovnikita.util.CustomHttpStatus.TOO_MANY_REQUESTS;
import static ru.vk.itmo.test.volkovnikita.util.Settings.REDIRECTED_HEADER;
import static ru.vk.itmo.test.volkovnikita.util.Settings.TIMESTAMP_HEADER;

public class HttpServerImpl extends HttpServer {

    private static final String PATH_NAME = "/v0/entity";
    private static final String TIMESTAMP = "X-timestamp";
    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;
    private static final Logger log = LoggerFactory.getLogger(HttpServerImpl.class);
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private final ExecutorService executor;
    private final HttpClient client;
    private final List<String> nodes;
    private final String selfUrl;

    private enum Methods {

        GET(1), PUT(5), DELETE(6);
        private final Integer code;

        Methods(Integer code) {
            this.code = code;
        }

        public Integer getCode() {
            return code;
        }
    }

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao, ExecutorService executorService) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
        this.executor = executorService;
        this.selfUrl = config.selfUrl();
        this.nodes = config.clusterUrls();
        this.client = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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

    public Response getEntry(String id) {
        try {
            TimestampEntry<MemorySegment> entry = dao.get(MemorySegment.ofArray(Utf8.toBytes(id)));
            if (entry == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }

            Response response;
            if (entry.value() == null) {
                response = new Response(Response.NOT_FOUND, Response.EMPTY);
            } else {
                response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            }
            response.addHeader(TIMESTAMP_HEADER + entry.timestamp());
            return response;
        } catch (Exception ex) {
            log.error("error in get: ", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public Response putEntry(String id, Request request, long timestamp) {
        if (request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        try {
            dao.upsert(new EntryWithTimestamp<>(
                    MemorySegment.ofArray(Utf8.toBytes(id)),
                    MemorySegment.ofArray(request.getBody()),
                    timestamp));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (Exception ex) {
            log.error("error in put", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public Response deleteEntry(String id, long timestamp) {
        try {
            dao.upsert(new EntryWithTimestamp<>(MemorySegment.ofArray(Utf8.toBytes(id)), null, timestamp));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (Exception ex) {
            log.error("error in delete", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String requestPath = request.getPath();
        if (PATH_NAME.equals(requestPath)) {
            handleHttpRequest(request, session);
        } else if ("/v0/entities".equals(requestPath)) {
            handleHttpRequestEntities(request, session);
        } else {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        }
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new CustomSession(socket, this);
    }

    private void handleHttpRequestEntities(Request request, HttpSession session) throws IOException {
        if (request.getMethod() != Request.METHOD_GET) {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
            return;
        }

        String startParam = request.getParameter("start=");
        String endParam = request.getParameter("end=");
        if (startParam == null || startParam.isEmpty() || (endParam != null && endParam.isEmpty())) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        try {
            executor.execute(() -> {
                MemorySegment startSegment = MemorySegment.ofArray(startParam.getBytes(StandardCharsets.UTF_8));
                MemorySegment endSegment = endParam == null
                        ? null
                        : MemorySegment.ofArray(endParam.getBytes(StandardCharsets.UTF_8));
                Iterator<TimestampEntry<MemorySegment>> entries = dao.get(startSegment, endSegment);
                try {
                    session.sendResponse(new ChunkHttpResponse(Response.OK, entries));
                } catch (IOException ex) {
                    log.error("Failed to send chunked response", ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            log.error("Request rejected", ex);
            session.sendResponse(new Response(TOO_MANY_REQUESTS.toString(), Response.EMPTY));
        }
    }

    private void handleHttpRequest(Request request, HttpSession session) throws IOException {
        try {
            String id = request.getParameter("id=");
            if (isIdIncorrect(id)) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }

            Methods method = getMethod(request.getMethod());
            if (method == null) {
                session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
                return;
            }

            int from = parseOrDefault(request.getParameter("from="), nodes.size());
            int ack = parseOrDefault(request.getParameter("ack="), from / 2 + 1);
            if (!isValidAckFrom(ack, from)) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }

            executor.execute(() -> processRequest(request, session, id, method, from, ack));
        } catch (RejectedExecutionException e) {
            log.error("Request rejected", e);
            session.sendResponse(new Response(TOO_MANY_REQUESTS.toString(), Response.EMPTY));
        }
    }

    private void processRequest(Request request, HttpSession session, String id, Methods method, int from, int ack) {
        try {
            process(request, session, id, method.name(), from, ack);
        } catch (Exception e) {
            log.error("error in processing request", e);
            sendError(session, e);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void process(Request request, HttpSession session, String id, String method, int from, int ack)
            throws IOException {
        if (isRedirected(request)) {
            session.sendResponse(handleLocalRequest(request, id));
            return;
        }

        List<String> sortedNodes = getSortNods(id, from);

        CompletableFuture<List<Response>> responses = collectResponses(request, sortedNodes, method, id, ack);

        responses.whenComplete(
                (responseList, ex) -> {
                    if (ex != null || responseList == null || responseList.size() < ack) {
                        try {
                            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS.toString(), Response.EMPTY));
                        } catch (IOException e) {
                            log.error("error in processing request", e);
                            sendError(session, e);
                        }
                        return;
                    }
                    try {
                        if (request.getMethod() == Request.METHOD_GET) {
                            session.sendResponse(validGetResponses(responseList.size(), responseList));
                        } else {
                            Response firstResponse = responseList.getFirst();
                            session.sendResponse(
                                    new Response(getProxyResponse(firstResponse.getStatus()), firstResponse.getBody()));
                        }
                    } catch (IOException e) {
                        sendError(session, e);
                    }
                }
        );
    }

    private boolean isRedirected(Request request) {
        return request.getHeader(REDIRECTED_HEADER) != null;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<List<Response>> collectResponses(
            Request request,
            List<String> nodes,
            String method,
            String id,
            int requiredAcks
    ) {
        CompletableFuture<List<Response>> futureResponses = new CompletableFuture<>();
        request.addHeader(TIMESTAMP_HEADER + System.currentTimeMillis());

        List<Response> responses = new CopyOnWriteArrayList<>();
        AtomicInteger successCounter = new AtomicInteger(requiredAcks);
        for (String node : nodes) {
            CompletableFuture<Response> futureResponse;
            if (responses.size() == requiredAcks) break;

            if (node.equals(selfUrl)) {
                futureResponse = getFutureLocalRequest(request, id);
            } else {
                futureResponse = redirectRequest(method, id, node, request);
            }
            futureResponse.whenComplete(
                    (response, ex) -> {
                        if (response == null) {
                            futureResponses.completeExceptionally(
                                    new NotEnoughReplicasException("Not Enough Replicas"));
                            return;
                        }
                        responses.add(response);
                        if (successCounter.decrementAndGet() == 0) {
                            futureResponses.complete(responses);
                        }
                    }
            );
        }
        return futureResponses;
    }

    private CompletableFuture<Response> getFutureLocalRequest(Request request, String id) {
        final CompletableFuture<Response> futureResponse = new CompletableFuture<>();
        executor.execute(() -> futureResponse.complete(handleLocalRequest(request, id)));
        return futureResponse;
    }

    private Response validGetResponses(int ack, List<Response> responses) {
        int notFoundCount = 0;
        long latestTimestamp = Long.MIN_VALUE;
        Response latestResponse = null;
        boolean latestIsNotFound = true;

        for (Response response : responses) {
            long timestamp = getTimestamp(response);

            if (response.getStatus() == HttpURLConnection.HTTP_NOT_FOUND) {
                notFoundCount++;
                if (timestamp > latestTimestamp) {
                    latestTimestamp = timestamp;
                    latestIsNotFound = true;
                }
            } else if (timestamp > latestTimestamp) {
                latestTimestamp = timestamp;
                latestResponse = response;
                latestIsNotFound = false;
            }
        }

        if (notFoundCount == ack || latestIsNotFound) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return new Response(Response.OK, latestResponse.getBody());
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<Response> redirectRequest(String method, String id, String clusterUrl, Request request) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s%s?id=%s", clusterUrl, PATH_NAME, id)))
                .method(method, HttpRequest.BodyPublishers
                        .ofByteArray(Optional.ofNullable(request.getBody()).orElse(new byte[0])))
                .header(REDIRECTED_HEADER, "true")
                .header(TIMESTAMP, Optional.ofNullable(request.getHeader(TIMESTAMP_HEADER)).orElse(""));

        final CompletableFuture<Response> futureResponse = new CompletableFuture<>();

        client.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((response, ex) -> {
                    if (ex != null) {
                        futureResponse.completeExceptionally(new IllegalArgumentException());
                        return;
                    }
                    Response newResponse = new Response(getProxyResponse(response.statusCode()), response.body());

                    response.headers().firstValue(TIMESTAMP)
                            .ifPresent(ts -> newResponse.addHeader(TIMESTAMP_HEADER + ts));

                    futureResponse.complete(newResponse);
                });

        return futureResponse;
    }

    private Response handleLocalRequest(Request request, String id) {
        long timestamp = Optional.ofNullable(request.getHeader(TIMESTAMP_HEADER))
                .map(Long::parseLong)
                .orElse(System.currentTimeMillis());

        return switch (request.getMethod()) {
            case Request.METHOD_GET -> getEntry(id);
            case Request.METHOD_PUT -> putEntry(id, request, timestamp);
            case Request.METHOD_DELETE -> deleteEntry(id, timestamp);
            default -> new Response(Response.BAD_REQUEST, Response.EMPTY);
        };
    }

    private void sendError(HttpSession session, Exception e) {
        try {
            String responseCode = e.getClass() == HttpException.class ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
            log.error("Send error", e);
            session.sendError(responseCode, null);
        } catch (Exception ex) {
            log.error("Unexpected error when sending error", ex);
        }
    }

    private List<String> getSortNods(String key, Integer from) {
        return nodes.stream()
                .sorted(Comparator.comparingInt(node -> Hash.murmur3(node + key)))
                .limit(from)
                .collect(Collectors.toList());
    }

    @Override
    public synchronized void stop() {
        super.stop();
        for (SelectorThread selector : selectors) {
            if (selector.selector.isOpen()) {
                for (Session session : selector.selector) {
                    session.close();
                }
            }
        }
    }

    private boolean isIdIncorrect(String id) {
        return id == null || id.isEmpty();
    }

    private String getProxyResponse(int code) {
        return switch (code) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            case 429 -> TOO_MANY_REQUESTS.toString();
            default -> Response.INTERNAL_ERROR;
        };
    }

    private Methods getMethod(int methodCode) {
        for (Methods method : Methods.values()) {
            if (method.getCode() == methodCode) {
                return method;
            }
        }
        return null;
    }

    private boolean isValidAckFrom(int ack, int from) {
        return ack > 0 && from > 0 && ack <= from && from <= nodes.size();
    }

    private int parseOrDefault(String parameter, int defaultValue) {
        try {
            return parameter == null || parameter.isEmpty() ? defaultValue : Integer.parseInt(parameter);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long getTimestamp(Response response) {
        String timestampHeader = response.getHeader(TIMESTAMP_HEADER);
        return timestampHeader != null ? Long.parseLong(timestampHeader) : 0L;
    }
}
