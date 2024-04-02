package ru.vk.itmo.test.volkovnikita;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.server.AcceptorConfig;
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

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static ru.vk.itmo.test.volkovnikita.util.CustomHttpStatus.TOO_LITTLE_REPLICAS;
import static ru.vk.itmo.test.volkovnikita.util.CustomHttpStatus.TOO_MANY_REQUESTS;
import static ru.vk.itmo.test.volkovnikita.util.Settings.REDIRECTED_HEADER;
import static ru.vk.itmo.test.volkovnikita.util.Settings.TIMESTAMP_HEADER;

public class HttpServerImpl extends HttpServer {

    private static final String PATH_NAME = "/v0/entity";
    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;
    private static final Logger log = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ExecutorService executor;
    private final HttpClient client;
    private final List<String> nodes;
    private final String selfUrl;

    private enum METHODS {

        GET(1), PUT(5), DELETE(6);
        private final Integer code;

        METHODS(Integer code) {
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
        this.client = HttpClient.newHttpClient();
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
        try {
            String id = request.getParameter("id=");
            if (isIdIncorrect(id)) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                return;
            }

            METHODS method = getMethod(request.getMethod());
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

    private void processRequest(Request request, HttpSession session, String id, METHODS method, int from, int ack) {
        try {
            process(request, session, id, method.name(), from, ack);
        } catch (Exception e) {
            log.error("error in processing request", e);
            sendError(session, e);
        }
    }

    private void process(Request request, HttpSession session, String id, String method, int from, int ack)
            throws IOException {
        if (isRedirected(request)) {
            session.sendResponse(handleLocalRequest(request, id));
            return;
        }

        List<String> sortedNodes = getSortNods(id, from);
        List<Response> responses = collectResponses(request, sortedNodes, method, id, ack);

        if (responses.size() < ack) {
            session.sendResponse(new Response(TOO_LITTLE_REPLICAS.toString(), Response.EMPTY));
            return;
        }

        sendFinalResponse(request, session, responses);
    }

    private boolean isRedirected(Request request) {
        return request.getHeader(REDIRECTED_HEADER) != null;
    }

    private List<Response> collectResponses(
            Request request,
            List<String> nodes,
            String method,
            String id,
            int requiredAcks
    ) {
        List<Response> responses = new ArrayList<>(requiredAcks);
        request.addHeader(TIMESTAMP_HEADER + System.currentTimeMillis());

        for (String node : nodes) {
            if (responses.size() == requiredAcks) break;
            try {
                responses.add(node.equals(selfUrl) ? handleLocalRequest(request, id) : redirectRequest(method, id, node, request));
            } catch (InterruptedException | IOException e) {
                log.error("Error sending request", e);
                Thread.currentThread().interrupt();
            }
        }
        return responses;
    }

    private void sendFinalResponse(Request request, HttpSession session, List<Response> responses) throws IOException {
        if (request.getMethod() == Request.METHOD_GET) {
            session.sendResponse(validGetResponses(responses.size(), responses));
        } else {
            Response firstResponse = responses.getFirst();
            session.sendResponse(new Response(getProxyResponse(firstResponse.getStatus()), firstResponse.getBody()));
        }
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


    private Response redirectRequest(String method, String id, String clusterUrl, Request request)
            throws InterruptedException, IOException {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s%s?id=%s", clusterUrl, PATH_NAME, id)))
                .method(method, HttpRequest.BodyPublishers
                        .ofByteArray(Optional.ofNullable(request.getBody()).orElse(new byte[0])))
                .header(REDIRECTED_HEADER, "true")
                .header("X-timestamp", Optional.ofNullable(request.getHeader(TIMESTAMP_HEADER)).orElse(""));

        HttpResponse<byte[]> httpResponse = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        Response response = new Response(getProxyResponse(httpResponse.statusCode()), httpResponse.body());

        httpResponse.headers().firstValue("X-timestamp")
                .ifPresent(ts -> response.addHeader(TIMESTAMP_HEADER + ts));
        return response;
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
                .toList();
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


    private METHODS getMethod(int methodCode) {
        for (METHODS method : METHODS.values()) {
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
