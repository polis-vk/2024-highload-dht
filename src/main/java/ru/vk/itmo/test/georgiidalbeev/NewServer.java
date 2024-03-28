package ru.vk.itmo.test.georgiidalbeev;

import one.nio.http.HttpException;
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
import ru.vk.itmo.test.georgiidalbeev.dao.BaseEntry;
import ru.vk.itmo.test.georgiidalbeev.dao.Dao;
import ru.vk.itmo.test.georgiidalbeev.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class NewServer extends HttpServer {

    private static final String PATH = "/v0/entity";
    private static final String HEADER_TIMESTAMP = "X-Timestamp: ";
    private static final String HEADER_INTERNAL = "X-Internal";
    private static final long MAX_RESPONSE_TIME = TimeUnit.SECONDS.toMillis(1);
    private static final List<Integer> methods = List.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE);
    private final Logger log = LoggerFactory.getLogger(NewServer.class);
    private final ThreadPoolExecutor executorService;
    private final HttpClient httpClient;
    private final ServiceConfig serviceConfig;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public NewServer(
            ServiceConfig config,
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ThreadPoolExecutor executorService,
            HttpClient httpClient
    ) throws IOException {
        super(configureServer(config));
        this.dao = dao;
        this.executorService = executorService;
        this.httpClient = httpClient;
        this.serviceConfig = config;
    }

    private static HttpServerConfig configureServer(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String key = request.getParameter("id=");
        if (key == null || key.isEmpty()) {
            sendResponseWithEmptyBody(session, Response.BAD_REQUEST);
            return;
        }

        int methodNumber = request.getMethod();
        if (!methods.contains(methodNumber)) {
            sendResponseWithEmptyBody(session, Response.METHOD_NOT_ALLOWED);
            return;
        }

        String fromString = request.getParameter("from=");
        String ackString = request.getParameter("ack=");
        int from = fromString == null || fromString.isEmpty()
                ? serviceConfig.clusterUrls().size()
                : Integer.parseInt(fromString);
        int ack = ackString == null || ackString.isEmpty() ? from / 2 + 1 : Integer.parseInt(ackString);

        if (ack == 0 || ack > from || from > serviceConfig.clusterUrls().size()) {
            sendResponseWithEmptyBody(session, Response.BAD_REQUEST);
            return;
        }
        long createdAt = System.currentTimeMillis();

        try {
            executorService.execute(() -> executeRequests(request, session, createdAt, key, ack, from));
        } catch (RejectedExecutionException e) {
            log.error("Workers pool queue overflow", e);
            session.sendError(NewResponseCodes.TOO_MANY_REQUESTS.getCode(), null);
        } catch (IllegalArgumentException e) {
            log.error("Method not allowed", e);
            session.sendError(Response.BAD_REQUEST, null);
        } catch (Exception e) {
            handleRequestException(session, e);
        }
    }

    private void executeRequests(Request request, HttpSession session, long createdAt, String key, int ack, int from) {
        if (isRequestTimeout(createdAt)) {
            sendResponseOrClose(session, new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }
        if (isInternalRequest(request)) {
            sendResponseOrClose(session, handleInternalRequest(request));
            return;
        }

        List<String> targetNodes = getNodesSortedByRendezvousHashing(key, serviceConfig, from);
        List<HttpRequest> httpRequests = createRequests(request, key, targetNodes, serviceConfig);
        List<Response> responses = getResponses(request, ack, httpRequests);

        if (isNotEnoughReplicas(responses, ack)) {
            sendResponseWithEmptyBody(session, NewResponseCodes.NOT_ENOUGH_REPLICAS.getCode());
            return;
        }

        try {
            if (isNotGetMethod(request)) {
                session.sendResponse(responses.getFirst());
                return;
            }

            mergeGetResponses(session, responses);
        } catch (IOException e) {
            logIOExceptionAndCloseSession(session, e);
        }
    }

    private boolean isRequestTimeout(long createdAt) {
        return System.currentTimeMillis() - createdAt > MAX_RESPONSE_TIME;
    }

    private boolean isInternalRequest(Request request) {
        return request.getHeader(HEADER_INTERNAL) != null;
    }

    private boolean isNotEnoughReplicas(List<Response> responses, int ack) {
        return responses.isEmpty() || responses.size() < ack;
    }

    private boolean isNotGetMethod(Request request) {
        return request.getMethod() != Request.METHOD_GET;
    }

    private void sendResponseOrClose(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            log.error("Exception while sending response", e);
            session.scheduleClose();
        }
    }

    private void mergeGetResponses(HttpSession session, List<Response> responses) throws IOException {
        byte[] body = null;
        int notFoundResponsesCount = 0;
        long maxTimestamp = Long.MIN_VALUE;
        for (Response response : responses) {
            long timestamp = response.getHeader(HEADER_TIMESTAMP) == null
                    ? -1
                    : Long.parseLong(response.getHeader(HEADER_TIMESTAMP));
            if (response.getStatus() == 404) {
                notFoundResponsesCount++;
                if (timestamp != -1 && maxTimestamp < timestamp) {
                    maxTimestamp = timestamp;
                    body = null;
                }
                continue;
            }
            if (maxTimestamp < timestamp) {
                maxTimestamp = timestamp;
                body = response.getBody();
            }
        }

        if (body != null && notFoundResponsesCount != responses.size()) {
            session.sendResponse(new Response(Response.OK, body));
            return;
        }
        sendResponseWithEmptyBody(session, Response.NOT_FOUND);
    }

    private List<Response> getResponses(Request request, int ack, List<HttpRequest> requests) {
        List<Response> responses = new ArrayList<>();
        int acceptableErrors = requests.size() - ack + 1;

        for (HttpRequest httpRequest : requests) {
            Response response = (httpRequest == null) ? handleInternalRequest(request) : proxyRequest(httpRequest);
            if (response == null && --acceptableErrors == 0) break;
            if (response != null && responses.add(response) && responses.size() == ack) break;
        }

        return responses;
    }

    private Response handleInternalRequest(Request request) {
        String id = request.getParameter("id=");
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        return switch (request.getMethod()) {
            case Request.METHOD_GET -> handleGet(id);
            case Request.METHOD_PUT -> handlePut(id, request.getBody());
            case Request.METHOD_DELETE -> handleDelete(id);
            default -> throw new IllegalArgumentException("Unsupported method: " + request.getMethod());
        };
    }

    private Response proxyRequest(HttpRequest httpRequest) {
        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            return proxyResponse(response, response.body());
        } catch (InterruptedException e) {
            log.error("Exception while sending request", e);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.info("Exception while sending request to another node", e);
            return null;
        }
    }

    private void sendResponseWithEmptyBody(HttpSession session, String status) {
        try {
            session.sendResponse(new Response(status, Response.EMPTY));
        } catch (IOException e) {
            logIOExceptionAndCloseSession(session, e);
        }
    }

    private void handleRequestException(HttpSession session, Exception e) {
        log.error("Error while handling request", e);
        try {
            if (e instanceof HttpException) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            logIOExceptionAndCloseSession(session, ex);
        }
    }

    private void logIOExceptionAndCloseSession(HttpSession session, IOException e) {
        log.error("Exception while sending close connection", e);
        session.scheduleClose();
    }

    private Response proxyResponse(HttpResponse<byte[]> response, byte[] body) {
        String responseCode = switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            default -> Response.INTERNAL_ERROR;
        };

        Response responseProxied = new Response(responseCode, body);
        long timestamp = response.headers().map().containsKey("x-timestamp")
                ? Long.parseLong(response.headers().map().get("x-timestamp").getFirst())
                : -1;
        if (timestamp != -1) {
            responseProxied.addHeader(HEADER_TIMESTAMP + timestamp);
        }
        return responseProxied;
    }

    List<String> getNodesSortedByRendezvousHashing(String key, ServiceConfig serviceConfig, int from) {
        Map<Integer, String> nodesHashes = new TreeMap<>();
        for (String nodeUrl : serviceConfig.clusterUrls()) {
            nodesHashes.put(Hash.murmur3(nodeUrl + key), nodeUrl);
        }
        return nodesHashes.values().stream().limit(from).collect(Collectors.toList());
    }

    public List<HttpRequest> createRequests(
            Request request,
            String key,
            List<String> targetNodes,
            ServiceConfig serviceConfig
    ) {
        List<HttpRequest> httpRequests = new ArrayList<>();
        for (String node : targetNodes) {
            if (node.equals(serviceConfig.selfUrl())) {
                httpRequests.add(null);
                continue;
            }
            HttpRequest subRequest = buildHttpRequest(key, node, request);
            httpRequests.add(subRequest);
        }
        return httpRequests;
    }

    public Response handleGet(String id) {
        MemorySegment msKey = toMemorySegment(id);

        Entry<MemorySegment> entry = dao.get(msKey);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        if (entry.value() == null) {
            Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(HEADER_TIMESTAMP + entry.timestamp());
            return response;
        }
        Response response = Response.ok(toByteArray(entry.value()));
        response.addHeader(HEADER_TIMESTAMP + entry.timestamp());
        return response;
    }

    public Response handlePut(String id, byte[] body) {
        MemorySegment msKey = toMemorySegment(id);
        Entry<MemorySegment> entry = new BaseEntry<>(msKey, MemorySegment.ofArray(body), System.currentTimeMillis());
        dao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response handleDelete(String id) {
        MemorySegment msKey = toMemorySegment(id);
        Entry<MemorySegment> entry = new BaseEntry<>(msKey, null, System.currentTimeMillis());
        dao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private HttpRequest buildHttpRequest(String key, String targetNode, Request request) {
        HttpRequest.Builder httpRequest = HttpRequest
                .newBuilder()
                .uri(URI.create(targetNode + PATH + "?id=" + key))
                .header(HEADER_INTERNAL, "true");

        switch (request.getMethod()) {
            case Request.METHOD_GET -> httpRequest.GET();
            case Request.METHOD_PUT -> httpRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
            case Request.METHOD_DELETE -> httpRequest.DELETE();
            default -> throw new IllegalArgumentException("Unsupported method: " + request.getMethod());
        }

        return httpRequest.build();
    }

    private MemorySegment toMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toByteArray(MemorySegment ms) {
        return ms.toArray(ValueLayout.JAVA_BYTE);
    }
}
