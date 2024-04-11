package ru.vk.itmo.test.klimplyasov;

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
import ru.vk.itmo.test.klimplyasov.dao.ReferenceBaseEntry;
import ru.vk.itmo.test.klimplyasov.dao.ReferenceDao;

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

public class PlyasovServer extends HttpServer {

    private static final String HEADER_REMOTE = "X-flag-remote-reference-server-to-node-by-plyasov";
    private static final String HEADER_REMOTE_ONE_NIO_HEADER = HEADER_REMOTE + ": da";
    private static final String HEADER_TIMESTAMP = "X-flag-remote-reference-server-to-node-by-plyasov2";
    private static final String HEADER_TIMESTAMP_ONE_NIO_HEADER = HEADER_TIMESTAMP + ": ";
    private static final Logger log = LoggerFactory.getLogger(PlyasovServer.class);
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private final ExecutorService executorLocal =
            Executors.newFixedThreadPool(THREADS / 2, new CustomThreadFactory("local-work"));
    private final ExecutorService executorRemote =
            Executors.newFixedThreadPool(THREADS / 2, new CustomThreadFactory("remote-work"));
    private final ReferenceDao dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public PlyasovServer(ServiceConfig config,
                         ReferenceDao dao) throws IOException {
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
        serverConfig.selectors = Runtime.getRuntime().availableProcessors() / 2;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (!isValidPath(request.getPath())) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (!isValidMethod(request.getMethod())) {
            session.sendError(Response.METHOD_NOT_ALLOWED, null);
            return;
        }

        String id = request.getParameter("id=");
        if (isInvalidId(id)) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (request.getHeader(HEADER_REMOTE_ONE_NIO_HEADER) != null) {
            handleRemoteRequest(request, session, id);
            return;
        }

        int ack = getInt(request, "ack=", config.clusterUrls().size() / 2 + 1);
        int from = getInt(request, "from=", config.clusterUrls().size());

        if (isInvalidFromOrAck(from, ack)) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        int[] indexes = getIndexes(id, from);
        MergeHandleResult mergeHandleResult = new MergeHandleResult(session, indexes.length, ack);
        for (int i = 0; i < indexes.length; i++) {
            int index = indexes[i];
            String executorNode = config.clusterUrls().get(index);
            if (executorNode.equals(config.selfUrl())) {
                handleAsync(executorLocal, i, mergeHandleResult, () -> handleLocalAsync(request, id));
            } else {
                handleAsync(executorRemote, i, mergeHandleResult, () -> handleRemoteAsync(request, executorNode));
            }
        }
    }

    private boolean isValidPath(String path) {
        return "/v0/entity".equals(path);
    }

    private boolean isValidMethod(int method) {
        return method == Request.METHOD_GET || method == Request.METHOD_DELETE || method == Request.METHOD_PUT;
    }

    private boolean isInvalidId(String id) {
        return id == null || id.isBlank();
    }

    private void handleRemoteRequest(Request request, HttpSession session, String id) {
        executorLocal.execute(() -> {
            try {
                HandleResult local = local(request, id);
                Response response = new Response(String.valueOf(local.status()), local.data());
                response.addHeader(HEADER_TIMESTAMP_ONE_NIO_HEADER + local.timestamp());
                session.sendResponse(response);
            } catch (Exception e) {
                logErrorAndHandleIOException(session, e);
            }
        });
    }

    private boolean isInvalidFromOrAck(int from, int ack) {
        return from <= 0 || from > config.clusterUrls().size() || ack > from || ack <= 0;
    }

    private void logErrorAndHandleIOException(HttpSession session, Exception e) {
        log.error("Exception during handleRequest", e);
        try {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            log.error("Exception while sending close connection", ex);
            session.scheduleClose();
        }
    }


    private CompletableFuture<HandleResult> handleRemoteAsync(final Request request, final String executorNode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return remote(request, executorNode);
            } catch (Exception e) {
                log.error("Exception during remote handling", e);
                return new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY);
            }
        }, executorRemote);
    }

    private CompletableFuture<HandleResult> handleLocalAsync(final Request request, final String executorNode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return local(request, executorNode);
            } catch (Exception e) {
                log.error("Exception during local handling", e);
                return new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY);
            }
        }, executorLocal);
    }

    private void handleAsync(final ExecutorService executor,
                             final int index,
                             final MergeHandleResult mergeHandleResult,
                             final ERunnable runnable) {
        CompletableFuture<HandleResult> futureResult;
        try {
            futureResult = runnable.run();
        } catch (Exception e) {
            log.error("Exception during handling", e);
            futureResult = CompletableFuture.completedFuture(
                            new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR,
                            Response.EMPTY)
                    );
        }

        futureResult.whenCompleteAsync((handleResult, throwable) -> {
            if (throwable != null) {
                log.error("Exception during handleAsync", throwable);
                handleResult = new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY);
            }
            mergeHandleResult.add(index, handleResult);
        }, executor);
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
                // todo ваша ошибка
                throw new IllegalArgumentException("parse error");
            }
        }
        return ack;
    }

    private HandleResult remote(Request request, String executorNode) {
        try {
            return invokeRemote(executorNode, request);
        } catch (IOException e) {
            log.info("I/O exception while calling remote node", e);
            return new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Thread interrupted");
            return new HandleResult(HttpURLConnection.HTTP_UNAVAILABLE, Response.EMPTY);
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

    private HandleResult invokeRemote(String executorNode, Request request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
            .method(
                request.getMethodName(),
                request.getBody() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
            )
            .header(HEADER_REMOTE, "da")
            .timeout(Duration.ofMillis(500))
            .build();
        HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
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
        return new HandleResult(httpResponse.statusCode(), httpResponse.body(), timestamp);
    }

    private HandleResult local(Request request, String id) {
        long currentTimeMillis = System.currentTimeMillis();
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                ReferenceBaseEntry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    return new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY);
                }
                if (entry.value() == null) {
                    return new HandleResult(
                            HttpURLConnection.HTTP_NOT_FOUND,
                            Response.EMPTY,
                            entry.timestamp());
                }

                return new HandleResult(
                        HttpURLConnection.HTTP_OK,
                        entry.value().toArray(ValueLayout.JAVA_BYTE),
                        entry.timestamp()
                );
            }
            case Request.METHOD_PUT -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new ReferenceBaseEntry<>(key, value, currentTimeMillis));
                return new HandleResult(HttpURLConnection.HTTP_CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                dao.upsert(new ReferenceBaseEntry<>(key, null, currentTimeMillis));
                return new HandleResult(HttpURLConnection.HTTP_ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new HandleResult(HttpURLConnection.HTTP_BAD_METHOD, Response.EMPTY);
            }
        }
    }

    // count <= config.clusterUrls().size()
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

    private interface ERunnable {
        CompletableFuture<HandleResult> run();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        PlyasovService.shutdownAndAwaitTermination(executorLocal);
        PlyasovService.shutdownAndAwaitTermination(executorRemote);
    }
}
