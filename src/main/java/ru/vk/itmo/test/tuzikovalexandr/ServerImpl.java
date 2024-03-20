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
    public static final int REQUEST_TIMEOUT = 10_000;
    private static final Map<Integer, String> HTTP_CODE = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR
    );

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
            long processingStartTime = System.currentTimeMillis();
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
        if (System.currentTimeMillis() - processingStartTime > 300) {
            session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        String paramId = request.getParameter("id=");

        if (paramId == null || paramId.isEmpty()) {
            sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        try {
            String nodeUrl = consistentHashing.getNode(paramId);
            log.error(nodeUrl);
            if (Objects.equals(selfUrl, nodeUrl)) {
                super.handleRequest(request, session);
            } else {
                handleProxyRequest(request, session, nodeUrl, paramId);
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

    private void sendException(HttpSession session, Exception exception) {
        try {
            String responseCode;
            if (exception.getClass().equals(TimeoutException.class)) {
                responseCode = Response.REQUEST_TIMEOUT;
            } else {
                responseCode = Response.INTERNAL_ERROR;
            }
            session.sendResponse(new Response(responseCode, Response.EMPTY));
        } catch (IOException e) {
            log.error("Error sending exception", e);
        }
    }

    private void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            log.error("Error sending response", e);
            session.scheduleClose();
        }
    }

    private HttpRequest createProxyRequest(Request request, String nodeUrl, String params) {
        return HttpRequest.newBuilder(URI.create(nodeUrl + "/v0/entity?id=" + params))
                .method(request.getMethodName(), request.getBody() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .build();
    }

    private void sendProxyRequest(HttpSession session, HttpRequest httpRequest) {
        try {
            HttpResponse<byte[]> httpResponse = httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .get(REQUEST_TIMEOUT, TimeUnit.MILLISECONDS);

            String statusCode = HTTP_CODE.getOrDefault(httpResponse.statusCode(), null);
            if (statusCode != null) {
                sendResponse(session, new Response(statusCode, httpResponse.body()));
            } else {
                sendResponse(session, new Response(Response.INTERNAL_ERROR, httpResponse.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendException(session, e);
        } catch (ExecutionException | TimeoutException e) {
            sendException(session, e);
        }
    }

    private void handleProxyRequest(Request request, HttpSession session, String nodeUrl, String params) {
        HttpRequest httpRequest = createProxyRequest(request, nodeUrl, params);

        if (httpRequest != null) {
            sendProxyRequest(session, httpRequest);
        } else {
            sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
        }
    }
}
