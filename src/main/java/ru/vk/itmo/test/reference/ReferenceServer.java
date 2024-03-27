package ru.vk.itmo.test.reference;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
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
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.*;

public class ReferenceServer extends HttpServer {

    private static final Logger log = LoggerFactory.getLogger(ReferenceServer.class);
    private final static int THREADS = Runtime.getRuntime().availableProcessors();

    private final ExecutorService executorLocal = Executors.newFixedThreadPool(THREADS / 2, new CustomThreadFactory("local-work"));
    private final ExecutorService executorRemote = Executors.newFixedThreadPool(THREADS / 2, new CustomThreadFactory("remote-work"));
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public ReferenceServer(ServiceConfig config,
                           Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createServerConfigWithPort(config.selfPort()));
        this.dao = dao;
        this.config = config;


        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static HttpServerConfig createServerConfigWithPort(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
//        acceptorConfig.threads = Runtime.getRuntime().availableProcessors() / 2;
        serverConfig.selectors = Runtime.getRuntime().availableProcessors() / 2;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (!"/v0/entity".equals(request.getPath())) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        String executorNode = getNodeByEntityId(id);
        if (executorNode.equals(config.selfUrl())) {
            handleAsync(session, executorLocal, () -> local(request, session, id));
        } else {
            handleAsync(session, executorRemote, () -> remote(request, session, executorNode));
        }
    }

    private void remote(Request request, HttpSession session, String executorNode) throws IOException {


        try {
            Response response = invokeRemote(executorNode, request);
            session.sendResponse(response);
        } catch (IOException e) {
            log.info("I/O exception while calling remote node", e);
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Thread interrupted");
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }

    }

    private void local(Request request, HttpSession session, String id) throws IOException {
        Response response = invokeLocal(request, id);
        session.sendResponse(response);
    }

    private void handleAsync(HttpSession session, ExecutorService executor, ERunnable runnable) throws IOException {
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

    @Override
    public synchronized void stop() {
        super.stop();
        ReferenceService.shutdownAndAwaitTermination(executorLocal);
        ReferenceService.shutdownAndAwaitTermination(executorRemote);
    }
}