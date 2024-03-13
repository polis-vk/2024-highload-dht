package ru.vk.itmo.test.tuzikovalexandr;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ServerImpl extends HttpServer {

    private final Dao dao;
    private final ExecutorService executorService;
    private final HttpClient httpClient;
    private final ConsistentHashing consistentHashing;
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);

    private final String selfUrl;
    private static final Set<Integer> METHODS = Set.of(
            Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE
    );
    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";

    public ServerImpl(ServiceConfig config, Dao dao, Worker worker,
                      ConsistentHashing consistentHashing) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;
        this.executorService = worker.getExecutorService();
        this.consistentHashing = consistentHashing;
        this.selfUrl = config.selfUrl();
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(2)).build();
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (METHODS.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            long processingStartTime = System.nanoTime();
            executorService.execute(() -> {
                try {
                    processingRequest(request, session, processingStartTime);
                } catch (IOException e) {
                    log.error("Exception while sending close connection", e);
                    session.scheduleClose();
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendResponse(new Response(TOO_MANY_REQUESTS, Response.EMPTY));
        }
    }

    @Path(value = "/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getEntry(@Param(value = "id", required = true) String id) {
        if (id.isEmpty() || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = fromString(id);
        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntry(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty() || id.isBlank() || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = fromString(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());

        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntry(@Param(value = "id", required = true) String id) {
        if (id.isEmpty() || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = fromString(id);
        dao.upsert(new BaseEntry<>(key, null));

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    private void processingRequest(Request request, HttpSession session, long processingStartTime) throws IOException {
        if (System.nanoTime() - processingStartTime > TimeUnit.SECONDS.toNanos(2)) {
            session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        String param = request.getParameter("id=");
        try {
            String nodeUrl = consistentHashing.getNode(param);
            if (Objects.equals(selfUrl, nodeUrl)) {
                super.handleRequest(request, session);
            } else {
                handleProxyRequest(request, session, nodeUrl, param);
            }
        } catch (Exception e) {
            if (e.getClass() == HttpException.class) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            } else {
                log.error("Exception during handleRequest: ", e);
                session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            }
        }
    }

    private static final Map<Integer, String> httpCodeMapping = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND
    );

    private static void sendExceptionInfo(HttpSession session, Exception exception) {
        try {
            String responseCode;
            if (exception.getClass().equals(TimeoutException.class)) {
                responseCode = Response.REQUEST_TIMEOUT;
            } else {
                responseCode = Response.INTERNAL_ERROR;
            }
            session.sendResponse(new Response(responseCode, exception.getMessage().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            log.error("Error while sending exception info", e);
        }
    }

    private void handleProxyRequest(Request request, HttpSession session, String nodeUrl, String params) {
        HttpRequest httpRequest;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(nodeUrl + "/v0/entity?id=" + params));
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                httpRequest = builder.GET().build();
            }
            case Request.METHOD_PUT -> {
                byte[] body = request.getBody();
                if (body == null) {
                    try {
                        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                    } catch (IOException e) {
                        log.error("Error sending response", e);
                    }
                    return;
                }
                httpRequest = builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            }
            case Request.METHOD_DELETE -> {
                httpRequest = builder.DELETE().build();
            }
            default -> {
                try {
                    session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                } catch (IOException e) {
                    log.error("Error sending response", e);
                }
                return;
            }
        }

        try {
            HttpResponse<byte[]> httpResponse = httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .get();

            String statusCode = httpCodeMapping.getOrDefault(httpResponse.statusCode(), null);
            if (statusCode != null) {
                try {
                    session.sendResponse(new Response(statusCode, httpResponse.body()));
                } catch (IOException e) {
                    log.error("Error sending response", e);
                }
            } else {
                try {
                    session.sendResponse(new Response(Response.INTERNAL_ERROR, httpResponse.body()));
                } catch (IOException e) {
                    log.error("Error sending response", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendExceptionInfo(session, e);
        } catch (ExecutionException e) {
            sendExceptionInfo(session, e);
        }
    }
}
