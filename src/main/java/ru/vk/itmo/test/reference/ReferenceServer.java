package ru.vk.itmo.test.reference;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import one.nio.util.Utf8;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class ReferenceServer extends HttpServer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceServer.class);

    private final Executor executor;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public ReferenceServer(ServiceConfig config,
                           Executor executor,
                           Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createServerConfigWithPort(config.selfPort()));
        this.executor = executor;
        this.dao = dao;
        this.config = config;
        this.httpClient = HttpClient.newHttpClient();
    }

    private static HttpServerConfig createServerConfigWithPort(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        handleAsync(session, () -> super.handleRequest(request, session));
    }

    private void handleAsync(HttpSession session, ERunnable runnable) throws IOException {
        try {
            executor.execute(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    try {
                        session.sendError(Response.INTERNAL_ERROR, null);
                    } catch (IOException ex) {
                        log.error("Exception while sending close connection", e);
                        session.scheduleClose();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Workers pool queue overflow", e);
            session.sendError(Response.SERVICE_UNAVAILABLE, null);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path("/v0/entity")
    public Response entity(Request request, @Param(value = "id") String id) {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String executorNode = getNodeByEntityId(id);

        if (executorNode.equals(config.selfUrl())) {
            return invokeLocal(request, id);
        }

        try {
            return invokeRemote(executorNode, request);
        } catch (HttpTimeoutException e) {
            log.info("timeout while invoking remote node");
            return new Response(Response.REQUEST_TIMEOUT, Response.EMPTY);
        } catch (IOException e) {
            log.info("I/O exception while calling remote node");
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Thread interrupted");
            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
        }
    }


    private Response invokeRemote(String executorNode, Request request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
            .method(
                request.getMethodName(),
                request.getBody() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
            )
            .timeout(Duration.ofMillis(500))
            .build();
        HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(Integer.toString(httpResponse.statusCode()), httpResponse.body());
    }

    private Response invokeLocal(Request request, String id) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                Entry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                }

                return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
            }
            case Request.METHOD_PUT -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new BaseEntry<>(key, value));
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                dao.upsert(new BaseEntry<>(key, null));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }


    private String getNodeByEntityId(String id) {
        int nodeId = 0;
        int maxHash = Hash.murmur3(config.clusterUrls().getFirst() + id);
        for (int i = 1; i < config.clusterUrls().size(); i++) {
            String url = config.clusterUrls().get(i);
            int result = Hash.murmur3(url + id);
            if (maxHash < result) {
                maxHash = result;
                nodeId = i;
            }
        }
        return config.clusterUrls().get(nodeId);
    }

    private interface ERunnable {
        void run() throws Exception;
    }
}