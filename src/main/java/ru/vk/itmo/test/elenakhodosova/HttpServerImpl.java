package ru.vk.itmo.test.elenakhodosova;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> dao;
    private static final String PATH_NAME = "/v0/entity";
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String TIMESTAMP_HEADER = "X-timestamp: ";
    private static final String REDIRECTED_HEADER = "X-redirected";
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
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            String id = request.getParameter("id=");
            if (isParamIncorrect(id)) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }
            executorService.execute(() -> processRequest(request, session, id));
        } catch (RejectedExecutionException e) {
            logger.error("Request rejected", e);
            session.sendResponse(new Response(TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    private void processRequest(Request request, HttpSession session, String id) {
        try {
            AllowedMethods method = getMethod(request.getMethod());
            if (method == null) {
                session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
                return;
            }

            String isRedirected = request.getHeader(REDIRECTED_HEADER);

            if (isRedirected != null) {
                session.sendResponse(handleLocalRequest(request, id));
                return;
            }

            String fromStr = request.getParameter("from=");
            String ackStr = request.getParameter("ack=");
            int from = fromStr == null || fromStr.isEmpty() ? nodes.size()
                    : Integer.parseInt(fromStr);
            int ack = ackStr == null || ackStr.isEmpty() ? from / 2 + 1 : Integer.parseInt(ackStr);
            if (ack == 0 || ack > from || from > nodes.size()) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }

            List<String> nodesHashes = getSortedNodes(id, from);
            int success = 0;
            Response[] responses = new Response[ack];


            for (int i = 0; i < from; i++) {
                if (success == ack) {
                    break;
                }
                String node = nodesHashes.get(i);
                try {
                    if (node.equals(selfUrl)) {
                        responses[success] = handleLocalRequest(request, id);
                    } else {
                        responses[success] = redirectRequest(method, id, node, request);
                    }
                    success++;
                } catch (InterruptedException | IOException e) {
                    logger.error("Error during sending request", e);
                    Thread.currentThread().interrupt();
                }

            }
            if (success < ack ) {
                session.sendResponse(new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY));
                return;
            }

            if (request.getMethod() == Request.METHOD_GET) {
                Response response = validateGetRequests(ack, responses);
                session.sendResponse(response);
                return;
            }
            String responseStatusCode = getResponseByCode(responses[0].getStatus());
            Response response = new Response(responseStatusCode, responses[0].getBody());
            session.sendResponse(response);
        } catch (Exception e) {
            logger.error("Unexpected error when processing request", e);
            sendError(session, e);
        }
    }

    private Response validateGetRequests(int ack, Response[] responses) {
        int notFound = 0;
        long latestTimestamp = 0L;
        Response latestResponse = null;
        boolean isLatestFailed = false;
        for (Response response : responses) {
            long timestamp = response.getHeader(TIMESTAMP_HEADER) == null ? 0L
                    : Long.parseLong(response.getHeader(TIMESTAMP_HEADER));
            if (response.getStatus() == HttpURLConnection.HTTP_NOT_FOUND) {
                notFound++;
                if (timestamp != 0L && latestTimestamp < timestamp) {
                    latestTimestamp = timestamp;
                   // latestResponse = response;
                   // latestResponse.setBody(null);
                    isLatestFailed = true;
                }
                continue;
            }
            isLatestFailed = false;
            if (timestamp > latestTimestamp) {
                latestTimestamp = timestamp;
                latestResponse = response;
            }
        }
        Response response;
        if (notFound == ack || isLatestFailed || latestResponse == null) {
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
        } else {
            response = new Response(Response.OK, latestResponse.getBody());
        }
        return response;
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
        return nodesHashes.values().stream().limit(from).toList();
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
                response = new Response(Response.NOT_FOUND, Response.EMPTY);
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

    private Response redirectRequest(AllowedMethods method,
                                     String id,
                                     String clusterUrl,
                                     Request request) throws InterruptedException, IOException {
        byte[] body = request.getBody();
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create(clusterUrl + PATH_NAME + "?id=" + id))
                .method(method.name(), body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .header(REDIRECTED_HEADER, "true")
                .header("X-timestamp", String.valueOf(System.currentTimeMillis())
                ).build(), HttpResponse.BodyHandlers.ofByteArray());
        Response result = new Response(getResponseByCode(response.statusCode()), response.body());
        Optional<String> respTimestamp = response.headers().firstValue("X-timestamp");
        respTimestamp.ifPresent(s -> System.out.println("DBG: " + s.toString()));
        respTimestamp.ifPresent(v-> result.addHeader(TIMESTAMP_HEADER + v));
        return result;
    }

    private Response handleLocalRequest(Request request, String id) {
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

    private AllowedMethods getMethod(int method) {
        if (method == 1) return AllowedMethods.GET;
        if (method == 5) return AllowedMethods.PUT;
        if (method == 6) return AllowedMethods.DELETE;
        return null;
    }
}
