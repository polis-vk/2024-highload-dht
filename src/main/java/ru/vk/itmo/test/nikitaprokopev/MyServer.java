package ru.vk.itmo.test.nikitaprokopev;

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

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import ru.vk.itmo.test.nikitaprokopev.dao.Dao;
import ru.vk.itmo.test.nikitaprokopev.dao.Entry;

public class MyServer extends HttpServer {
    private static final String HEADER_TIMESTAMP = "X-Timestamp: ";
    private static final String HEADER_INTERNAL = "X-Internal";
    private static final int NOT_FOUND_CODE = 404;
    private static final long MAX_RESPONSE_TIME = TimeUnit.SECONDS.toMillis(1);
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

        if (ack == 0 || ack > from || from > serviceConfig.clusterUrls().size()) {
            sendResponseWithEmptyBody(session, Response.BAD_REQUEST);
            return;
        }
        long createdAt = System.currentTimeMillis();

        try {
            workerPool.execute(() -> executeRequests(request, session, createdAt, key, ack, from));
        } catch (RejectedExecutionException e) {
            log.error("Workers pool queue overflow", e);
            session.sendError(CustomResponseCodes.TOO_MANY_REQUESTS.getCode(), null);
        } catch (IllegalArgumentException e) {
            log.error("Method not allowed", e);
            session.sendError(Response.BAD_REQUEST, null);
        } catch (Exception e) {
            handleRequestException(session, e);
        }
    }

    private void executeRequests(Request request, HttpSession session, long createdAt, String key, int ack, int from) {
        if (System.currentTimeMillis() - createdAt > MAX_RESPONSE_TIME) {
            try {
                session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            } catch (IOException e) {
                log.error("Exception while sending close connection", e);
                session.scheduleClose();
            }
            return;
        }
        if (request.getHeader(HEADER_INTERNAL) != null) {
            Response response = handleInternalRequest(request);
            try {
                session.sendResponse(response);
            } catch (IOException e) {
                log.error("Exception while sending response", e);
                session.scheduleClose();
            }
            return;
        }

        List<String> targetNodes = getNodesSortedByRendezvousHashing(key, serviceConfig, from);
        List<HttpRequest> httpRequests =
                requestHandler.createRequests(request, key, targetNodes, serviceConfig);
        List<Response> responses = getResponses(request, ack, httpRequests);
        if (responses.isEmpty() || responses.size() < ack) {
            sendResponseWithEmptyBody(session, CustomResponseCodes.RESPONSE_NOT_ENOUGH_REPLICAS.getCode());
            return;
        }
        try {
            if (request.getMethod() != Request.METHOD_GET) {
                session.sendResponse(responses.getFirst());
                return;
            }

            mergeGetResponses(session, responses);
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
            long timestamp = response.getHeader(HEADER_TIMESTAMP) == null ? -1
                    : Long.parseLong(response.getHeader(HEADER_TIMESTAMP));
            if (response.getStatus() == NOT_FOUND_CODE) {
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

    private List<Response> getResponses(
            Request request,
            int ack,
            List<HttpRequest> requests
    ) {
        List<Response> responses = new ArrayList<>();
        // stop if ack responses are received or too many errors
        int successCounter = ack;
        int acceptableErrors = requests.size() - ack + 1;
        for (HttpRequest httpRequest : requests) {
            Response response;
            if (httpRequest == null) {
                response = handleInternalRequest(request);
            } else {
                response = proxyRequest(httpRequest);
            }
            if (response == null) {
                --acceptableErrors;
                if (acceptableErrors == 0) {
                    break;
                }
                continue;
            }
            responses.add(response);
            --successCounter;
            if (successCounter == 0) {
                break;
            }
        }
        return responses;
    }

    private Response handleInternalRequest(Request request) {
        Response response;
        String id = request.getParameter("id=");
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        response = switch (request.getMethod()) {
            case Request.METHOD_GET -> requestHandler.handleGet(id);
            case Request.METHOD_PUT -> requestHandler.handlePut(id, request.getBody());
            case Request.METHOD_DELETE -> requestHandler.handleDelete(id);
            default -> throw new IllegalArgumentException("Unsupported method: " + request.getMethod());
        };
        return response;
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
            log.error("Exception while sending close connection", e);
            session.scheduleClose();
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
            log.error("Exception while sending close connection", e);
            session.scheduleClose();
        }
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

        return nodesHashes.values().stream()
                .limit(from)
                .toList();
    }
}
