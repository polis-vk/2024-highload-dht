package ru.vk.itmo.test.nikitaprokopev;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.nikitaprokopev.dao.Dao;
import ru.vk.itmo.test.nikitaprokopev.dao.Entry;
import ru.vk.itmo.test.nikitaprokopev.exceptions.NotEnoughReplicasException;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MyServer extends HttpServer {
    public static final String HEADER_TIMESTAMP = "X-Timestamp: ";
    public static final String HEADER_TIMESTAMP_LOWER_CASE = "x-timestamp";
    public static final String HEADER_INTERNAL = "X-Internal";
    public static final String LOCAL_REQUEST = "LOCAL";
    private static final int NOT_FOUND_CODE = 404;
    private static final long MAX_RESPONSE_TIME_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final List<Integer> allowedMethods = List.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );
    private final Logger log = LoggerFactory.getLogger(MyServer.class);
    private final ThreadPoolExecutor workerPool;
    private final HttpClient httpClient;
    private final ServiceConfig serviceConfig;
    private RequestHandler requestHandler;

    public MyServer(ServiceConfig serviceConfig,
                    Dao<MemorySegment, Entry<MemorySegment>> dao,
                    ThreadPoolExecutor workerPool,
                    HttpClient httpClient
    ) throws IOException {
        super(createServerConfig(serviceConfig));
        this.workerPool = workerPool;
        this.httpClient = httpClient;
        this.serviceConfig = serviceConfig;
        this.requestHandler = new RequestHandler(dao);
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

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        switch (request.getPath()) {
            case "/v0/entity" -> handleEntity(request, session);
            case "/v0/entities" -> handleEntities(request, session);
            default -> sendResponseWithEmptyBody(session, Response.BAD_REQUEST);
        }
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new MySession(socket, this);
    }

    private void handleEntity(Request request, HttpSession session) throws IOException {
        String key = request.getParameter("id=");
        if (key == null || key.isEmpty()) {
            sendResponseWithEmptyBody(session, Response.BAD_REQUEST);
            return;
        }

        int methodNum = request.getMethod();
        if (!allowedMethods.contains(methodNum)) {
            sendResponseWithEmptyBody(session, Response.METHOD_NOT_ALLOWED);
            return;
        }

        String fromString = request.getParameter("from=");
        String ackString = request.getParameter("ack=");
        int from = fromString == null || fromString.isEmpty() ? serviceConfig.clusterUrls().size()
                : Integer.parseInt(fromString);
        int ack = ackString == null || ackString.isEmpty() ? from / 2 + 1 : Integer.parseInt(ackString);

        if (ack <= 0 || ack > from || from > serviceConfig.clusterUrls().size()) {
            sendResponseWithEmptyBody(session, Response.BAD_REQUEST);
            return;
        }
        long createdAt = System.currentTimeMillis();

        try {
            workerPool.execute(() -> executeRequests(request, session, createdAt, key, ack, from));
        } catch (RejectedExecutionException e) {
            log.error("Workers pool queue overflow", e);
            session.sendError(CustomResponseCodes.TOO_MANY_REQUESTS.getCode(), null);
        }
    }

    private void handleEntities(Request request, HttpSession session) throws IOException {
        if (request.getMethod() != Request.METHOD_GET) {
            sendResponseWithEmptyBody(session, Response.METHOD_NOT_ALLOWED);
            return;
        }

        String start = request.getParameter("start=");
        String end = request.getParameter("end=");
        if (start == null || start.isEmpty() || (end != null && end.isEmpty())) {
            sendResponseWithEmptyBody(session, Response.BAD_REQUEST);
            return;
        }
        try {
            workerPool.execute(() -> {
                Iterator<Entry<MemorySegment>> entries = requestHandler.handleEntities(start, end);

                try {
                    session.sendResponse(new MyChunkedResponse(Response.OK, entries));
                } catch (IOException e) {
                    logIOExceptionAndCloseSession(session, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Workers pool queue overflow", e);
            session.sendError(CustomResponseCodes.TOO_MANY_REQUESTS.getCode(), null);
        }
    }

    private void executeRequests(Request request, HttpSession session, long createdAt, String key, int ack, int from) {
        if (System.currentTimeMillis() - createdAt > MAX_RESPONSE_TIME_MILLIS) {
            try {
                session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            } catch (IOException e) {
                logIOExceptionAndCloseSession(session, e);
            }
            return;
        }
        if (request.getHeader(HEADER_INTERNAL) != null) {
            Response response = handleInternalRequest(request);
            try {
                session.sendResponse(response);
            } catch (IOException e) {
                logIOExceptionAndCloseSession(session, e);
            }
            return;
        }

        Collection<String> targetNodes = getNodesSortedByRendezvousHashing(key, serviceConfig, from);
        Collection<HttpRequest> httpRequests =
                requestHandler.createRequests(request, key, targetNodes, serviceConfig.selfUrl());
        CompletableFuture<List<Response>> responses = getResponses(request, ack, httpRequests);
        responses = responses.whenComplete(
                (list, throwable) -> {
                    if (throwable != null || list == null || list.size() < ack) {
                        sendResponseWithEmptyBody(session, CustomResponseCodes.RESPONSE_NOT_ENOUGH_REPLICAS.getCode());
                        return;
                    }
                    try {
                        if (request.getMethod() != Request.METHOD_GET) {
                            session.sendResponse(list.getFirst());
                            return;
                        }

                        mergeGetResponses(session, list);
                    } catch (IOException e) {
                        logIOExceptionAndCloseSession(session, e);
                    }
                }
        );
        checkFuture(responses);
    }

    private void mergeGetResponses(HttpSession session, List<Response> responses) throws IOException {
        byte[] body = null;
        long maxTimestamp = Long.MIN_VALUE;
        for (Response response : responses) {
            long timestamp = response.getHeader(HEADER_TIMESTAMP) == null ? -1
                    : Long.parseLong(response.getHeader(HEADER_TIMESTAMP));
            if (timestamp > maxTimestamp) {
                maxTimestamp = timestamp;
                body = response.getStatus() == NOT_FOUND_CODE ? null : response.getBody();
            }
        }

        if (body != null) {
            session.sendResponse(new Response(Response.OK, body));
            return;
        }
        sendResponseWithEmptyBody(session, Response.NOT_FOUND);
    }

    private CompletableFuture<List<Response>> getResponses(
            Request request,
            int ack,
            Collection<HttpRequest> requests
    ) {
        final CompletableFuture<List<Response>> result = new CompletableFuture<>();
        List<Response> responses = new CopyOnWriteArrayList<>();
        AtomicInteger successCounter = new AtomicInteger(ack);
        AtomicInteger leftErrorsToFail = new AtomicInteger(requests.size() - ack + 1);
        for (HttpRequest httpRequest : requests) {
            CompletableFuture<Response> cfResponse;
            if (httpRequest.headers().map().containsKey(LOCAL_REQUEST)) {
                request.addHeader(
                        HEADER_TIMESTAMP + httpRequest.headers().map().get(HEADER_TIMESTAMP_LOWER_CASE).getFirst());
                cfResponse = getInternalResponse(request);
            } else {
                cfResponse = proxyRequest(httpRequest);
            }
            cfResponse = cfResponse.whenComplete(
                    (response, throwable) -> {
                        if (response == null) {
                            if (leftErrorsToFail.decrementAndGet() == 0) {
                                result.completeExceptionally(new NotEnoughReplicasException());
                            }
                            return;
                        }
                        responses.add(response);
                        if (successCounter.decrementAndGet() == 0) {
                            result.complete(responses);
                        }
                    }
            );
            checkFuture(cfResponse);
        }
        return result;
    }

    private CompletableFuture<Response> getInternalResponse(Request request) {
        final CompletableFuture<Response> cfResponse = new CompletableFuture<>();
        workerPool.execute(
                () -> {
                    try {
                        cfResponse.complete(handleInternalRequest(request));
                    } catch (IllegalArgumentException e) {
                        cfResponse.completeExceptionally(e);
                    }
                });
        return cfResponse;
    }

    private Response handleInternalRequest(Request request) {
        Response response;
        String id = request.getParameter("id=");
        long timestamp = request.getHeader(HEADER_TIMESTAMP_LOWER_CASE) == null
                ? System.currentTimeMillis()
                : Long.parseLong(request.getHeader(HEADER_TIMESTAMP.toLowerCase()));
        response = switch (request.getMethod()) {
            case Request.METHOD_GET -> requestHandler.handleGet(id);
            case Request.METHOD_PUT -> requestHandler.handlePut(id, request.getBody(), timestamp);
            case Request.METHOD_DELETE -> requestHandler.handleDelete(id, timestamp);
            default -> throw new IllegalArgumentException("Unsupported method: " + request.getMethod());
        };
        return response;
    }

    private CompletableFuture<Response> proxyRequest(HttpRequest httpRequest) {
        final CompletableFuture<Response> cfResponse = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> response =
                httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                        .whenComplete(
                                (httpResponse, throwable) -> {
                                    if (throwable != null) {
                                        cfResponse.completeExceptionally(throwable);
                                        return;
                                    }
                                    cfResponse.complete(
                                            proxyResponse(httpResponse)
                                    );
                                }
                        );
        checkFuture(response);
        return cfResponse;
    }

    private void checkFuture(CompletableFuture<?> cf) {
        if (cf == null) {
            log.error("Error while working with CompletableFuture");
        }
    }

    private void sendResponseWithEmptyBody(HttpSession session, String status) {
        try {
            session.sendResponse(new Response(status, Response.EMPTY));
        } catch (IOException e) {
            logIOExceptionAndCloseSession(session, e);
        }
    }

    private void logIOExceptionAndCloseSession(HttpSession session, IOException e) {
        log.error("Exception while sending close connection", e);
        session.scheduleClose();
    }

    private Response proxyResponse(HttpResponse<byte[]> response) {
        String responseCode = switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            default -> Response.INTERNAL_ERROR;
        };

        Response responseProxied = new Response(responseCode, response.body());
        long timestamp = response.headers().map().containsKey(HEADER_TIMESTAMP_LOWER_CASE)
                ? Long.parseLong(response.headers().map().get(HEADER_TIMESTAMP_LOWER_CASE).getFirst())
                : -1;
        if (timestamp != -1) {
            responseProxied.addHeader(HEADER_TIMESTAMP + timestamp);
        }
        return responseProxied;
    }

    List<String> getNodesSortedByRendezvousHashing(String key, ServiceConfig serviceConfig, int from) {
        TreeMap<Integer, String> nodesHashes = new TreeMap<>();

        for (String nodeUrl : serviceConfig.clusterUrls()) {
            nodesHashes.put(Hash.murmur3(nodeUrl + key), nodeUrl);
            if (nodesHashes.size() > from) {
                nodesHashes.remove(nodesHashes.lastKey());
            }
        }

        return new ArrayList<>(nodesHashes.values());
    }
}
