package ru.vk.itmo.test.georgiidalbeev;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceBaseEntry;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static ru.vk.itmo.test.georgiidalbeev.MyReferenceService.shutdownAndAwaitTermination;

public class MyReferenceServer extends HttpServer {

    private static final String HEADER_REMOTE = "X-flag-remote-reference-server-to-node-by-paschenko";
    private static final String HEADER_REMOTE_ONE_NIO_HEADER = HEADER_REMOTE + ": da";
    private static final String HEADER_TIMESTAMP = "X-flag-remote-reference-server-to-node-by-paschenko2";
    private static final String HEADER_TIMESTAMP_ONE_NIO_HEADER = HEADER_TIMESTAMP + ": ";
    private static final Logger log = LoggerFactory.getLogger(MyReferenceServer.class);
    private static final int THREADS = Runtime.getRuntime().availableProcessors();

    private final ExecutorService executorLocal = Executors.newFixedThreadPool(THREADS / 2,
            new CustomThreadFactory("local-work"));
    private final ExecutorService executorRemote = Executors.newFixedThreadPool(THREADS / 2,
            new CustomThreadFactory("remote-work"));
    private final ReferenceDao dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public MyReferenceServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createServerConfigWithPort(config.selfPort()));
        this.dao = dao;
        this.config = config;

        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    private static HttpServerConfig createServerConfigWithPort(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        serverConfig.selectors = Runtime.getRuntime().availableProcessors() / 2;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (!"/v0/entity".equals(request.getPath())) {
            sendError(session, Response.BAD_REQUEST);
            return;
        }

        if (!isValidMethod(request.getMethod())) {
            sendError(session, Response.METHOD_NOT_ALLOWED);
            return;
        }

        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            sendError(session, Response.BAD_REQUEST);
            return;
        }

        if (request.getHeader(HEADER_REMOTE_ONE_NIO_HEADER) != null) {
            handleLocalRequest(request, session, id);
            return;
        }

        int ack = getInt(request, "ack=", config.clusterUrls().size() / 2 + 1);
        int from = getInt(request, "from=", config.clusterUrls().size());

        if (!isValidAckFrom(ack, from)) {
            sendError(session, Response.BAD_REQUEST);
            return;
        }

        handleRemoteRequest(request, session, id, ack, from);
    }

    private void sendError(HttpSession session, String errorCode) throws IOException {
        session.sendError(errorCode, null);
    }

    private boolean isValidMethod(int method) {
        return method == Request.METHOD_GET || method == Request.METHOD_DELETE || method == Request.METHOD_PUT;
    }

    private void handleLocalRequest(Request request, HttpSession session, String id) {
        executorLocal.execute(() -> {
            try {
                MyHandleResult local = local(request, id);
                Response response = new Response(String.valueOf(local.status()), local.data());
                response.addHeader(HEADER_TIMESTAMP_ONE_NIO_HEADER + local.timestamp());
                session.sendResponse(response);
            } catch (Exception e) {
                handleError(session, e);
            }
        });
    }

    private boolean isValidAckFrom(int ack, int from) {
        return from > 0 && from <= config.clusterUrls().size() && ack <= from && ack > 0;
    }

    private void handleRemoteRequest(Request request, HttpSession session, String id, int ack, int from) {
        int[] indexes = getIndexes(id, from);
        MyMergeHandleResult mergeHandleResult = new MyMergeHandleResult(session, indexes.length, ack);
        for (int index : indexes) {
            String executorNode = config.clusterUrls().get(index);
            if (executorNode.equals(config.selfUrl())) {
                handleAsync(mergeHandleResult, () -> CompletableFuture.completedFuture(local(request, id)));
            } else {
                handleAsync(mergeHandleResult, () -> invokeRemote(executorNode, request));
            }
        }
    }

    private void handleError(HttpSession session, Exception e) {
        log.error("Exception during handleRequest", e);
        try {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            log.error("Exception while sending close connection", e);
            session.scheduleClose();
        }
    }

    private int getInt(Request request, String param, int defaultValue) throws IOException {
        int ack;
        String ackStr = request.getParameter(param);
        if (ackStr == null || ackStr.isBlank()) {
            ack = defaultValue;
        } else {
            try {
                ack = Integer.parseInt(ackStr);
            } catch (Exception e) {
                throw new IllegalArgumentException("parse error");
            }
        }
        return ack;
    }

    private void handleAsync(MyMergeHandleResult mergeHandleResult,
                             Supplier<CompletableFuture<MyHandleResult>> supplier) {
        supplier.get().thenAccept(mergeHandleResult::add).exceptionally(e -> {
            log.error("Exception during handleRequest", e);
            mergeHandleResult.add(new MyHandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
            return null;
        });
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    private CompletableFuture<MyHandleResult> invokeRemote(String executorNode, Request request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
                .method(request.getMethodName(), request.getBody() == null ? HttpRequest.BodyPublishers.noBody() :
                        HttpRequest.BodyPublishers.ofByteArray(request.getBody())).header(HEADER_REMOTE, "da")
                .timeout(Duration.ofMillis(500)).build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray()).thenApply(httpResponse -> {
            Optional<String> string = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
            long timestamp;
            if (string.isPresent()) {
                try {
                    timestamp = Long.parseLong(string.get());
                } catch (Exception e) {
                    log.error("todo ");
                    timestamp = 0;
                }
            } else {
                timestamp = 0;
            }
            return new MyHandleResult(httpResponse.statusCode(), httpResponse.body(), timestamp);
        }).exceptionally(e -> {
            log.info("I/O exception while calling remote node", e);
            return new MyHandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY);
        });
    }

    private MyHandleResult local(Request request, String id) {
        long currentTimeMillis = System.currentTimeMillis();
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                ReferenceBaseEntry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    return new MyHandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY);
                }
                if (entry.value() == null) {
                    return new MyHandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY, entry.timestamp());
                }

                return new MyHandleResult(HttpURLConnection.HTTP_OK,
                        entry.value().toArray(ValueLayout.JAVA_BYTE),
                        entry.timestamp());
            }
            case Request.METHOD_PUT -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new ReferenceBaseEntry<>(key, value, currentTimeMillis));
                return new MyHandleResult(HttpURLConnection.HTTP_CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                dao.upsert(new ReferenceBaseEntry<>(key, null, currentTimeMillis));
                return new MyHandleResult(HttpURLConnection.HTTP_ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new MyHandleResult(HttpURLConnection.HTTP_BAD_METHOD, Response.EMPTY);
            }
        }
    }

    private int[] getIndexes(String id, int count) {
        assert count < 5;

        int[] result = new int[count];
        int[] maxHashs = new int[count];

        for (int i = 0; i < count; i++) {
            String url = config.clusterUrls().get(i);
            int hash = Hash.murmur3(url + id);
            result[i] = i;
            maxHashs[i] = hash;
        }

        for (int i = count; i < config.clusterUrls().size(); i++) {
            String url = config.clusterUrls().get(i);
            int hash = Hash.murmur3(url + id);
            for (int j = 0; j < maxHashs.length; j++) {
                int maxHash = maxHashs[j];
                if (maxHash < hash) {
                    maxHashs[j] = hash;
                    result[j] = i;
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        shutdownAndAwaitTermination(executorLocal);
        shutdownAndAwaitTermination(executorRemote);
    }
}
