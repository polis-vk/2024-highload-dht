package ru.vk.itmo.test.volkovnikita;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;
import ru.vk.itmo.test.volkovnikita.util.CustomHttpStatus;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final Logger log = LoggerFactory.getLogger(HttpServerImpl.class);
    private final Executor executor;
    private static final Integer KEEP_ALIVE = 30;
    private static final Integer QUEUE_SIZE = 1500;
    private static final Integer POOL_SIZE = 8;
    static final Set<String> METHODS = Set.of(
            "GET",
            "PUT",
            "DELETE"
    );
    private static final ZoneId SERVER_ZONE = ZoneId.of("UTC");
    private final HttpClient client;
    private final List<String> nodes;
    private final String selfUrl;

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createServerConfig(config));
        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory = r ->
                new Thread(r, "HttpServerImplThread: " + threadCounter.getAndIncrement());

        this.executor = new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                KEEP_ALIVE,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.dao = dao;
        this.selfUrl = config.selfUrl();
        this.nodes = config.clusterUrls();
        this.client = HttpClient.newHttpClient();
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Path(value = "/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getEntry(@Param(value = "id", required = true) String id, Request request) {
        if (isIdIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String clusterUrl = hash(id);
        if (!Objects.equals(clusterUrl, selfUrl)) {
            return forwardRequest(request, clusterUrl, id);
        }

        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        Entry<MemorySegment> entry = dao.get(key);
        return entry == null ? new Response(Response.NOT_FOUND, Response.EMPTY) :
                new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntry(@Param(value = "id", required = true) String id, Request request) {
        if (isIdIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String clusterUrl = hash(id);
        if (!Objects.equals(clusterUrl, selfUrl)) {
            return forwardRequest(request, clusterUrl, id);
        }

        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntry(@Param(value = "id", required = true) String id, Request request) {
        if (isIdIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String clusterUrl = hash(id);
        if (!Objects.equals(clusterUrl, selfUrl)) {
            return forwardRequest(request, clusterUrl, id);
        }

        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        Response response;
        if (METHODS.contains(request.getMethodName())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
            sendResponse(session, response);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            sendResponse(session, response);
        }
        sendResponse(session, response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        Duration timeout = Duration.of(1, ChronoUnit.SECONDS);
        LocalDateTime deadlineRequest = LocalDateTime.now(SERVER_ZONE).plus(timeout);
        try {
            executor.execute(() -> process(request, session, deadlineRequest));
        } catch (RejectedExecutionException e) {
            log.error(e.toString());
            sendResponse(session, new Response(CustomHttpStatus.TOO_MANY_REQUESTS.toString(), Response.EMPTY));
        }
    }

    private void process(Request request, HttpSession session, LocalDateTime deadlineRequest) {
        LocalDateTime now = LocalDateTime.now(SERVER_ZONE);
        if (now.isAfter(deadlineRequest)) {
            sendResponse(session, new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            boolean isHttp = e.getClass() == HttpException.class;

            sendResponse(session, isHttp ? new Response(Response.BAD_REQUEST, Response.EMPTY) :
                    new Response(Response.INTERNAL_ERROR, Response.EMPTY));

            log.error(e.toString());
        }

    }

    private Response forwardRequest(Request request, String clusterUrl, String id) {
        URI uri = URI.create(String.format("%s/v0/entity?id=%s", clusterUrl, id));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri);

        switch (request.getMethod()) {
            case Request.METHOD_GET -> requestBuilder.GET();
            case Request.METHOD_PUT -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
            case Request.METHOD_DELETE -> requestBuilder.DELETE();
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }

        try {
            HttpResponse<byte[]> httpResponse =
                    client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(getResponseByCode(httpResponse.statusCode()), httpResponse.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (IOException e) {
            log.error("Exception in forward request at sending request", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private boolean isIdIncorrect(String id) {
        return id == null || id.isEmpty() || id.isBlank();
    }

    private void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            session.handleException(e);
        }
    }

    private String hash(String id) {
        int maxHash = Integer.MIN_VALUE;
        String nodeUrl = null;
        for (String node : nodes) {
            int hash = Hash.murmur3(id + node);
            if (hash > maxHash) {
                maxHash = hash;
                nodeUrl = node;
            }
        }
        return nodeUrl;
    }

    private String getResponseByCode(int code) {
        return switch (code) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            case 429 -> CustomHttpStatus.TOO_MANY_REQUESTS.toString();
            default -> Response.INTERNAL_ERROR;
        };
    }
}
