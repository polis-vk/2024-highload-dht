package ru.vk.itmo.test.timofeevkirill.reference;

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
import ru.vk.itmo.test.timofeevkirill.reference.dao.ReferenceBaseEntry;
import ru.vk.itmo.test.timofeevkirill.reference.dao.ReferenceDao;

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

public class ReferenceServer extends HttpServer {

    private static final String HEADER_REMOTE = "X-flag-remote-reference-server-to-node";
    private static final String HEADER_REMOTE_VALUE = "true";
    private static final String HEADER_REMOTE_ONE_NIO_HEADER = HEADER_REMOTE + ": " + HEADER_REMOTE_VALUE;
    private static final String HEADER_TIMESTAMP = "X-timestamp-reference-server-to-node";
    private static final String HEADER_TIMESTAMP_ONE_NIO_HEADER = HEADER_TIMESTAMP + ": ";
    private static final String BASE_PATH = "/v0/entity";
    private static final String UNPROCESSABLE_CONTENT_RESPONSE = "422 Unprocessable Content";
    private static final String ID_PARAMETER_KEY = "id=";
    private static final String ACK_PARAMETER_KEY = "ack=";
    private static final String FROM_PARAMETER_KEY = "from=";
    private static final Logger log = LoggerFactory.getLogger(ReferenceServer.class);
    private final int THREADS = Runtime.getRuntime().availableProcessors();

    private final ExecutorService executorLocal =
            Executors.newFixedThreadPool(THREADS / 2, new CustomThreadFactory("local-work"));
    private final ExecutorService executorRemote =
            Executors.newFixedThreadPool(THREADS / 2, new CustomThreadFactory("remote-work"));
    private final ReferenceDao dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public ReferenceServer(ServiceConfig config,
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
        if (!BASE_PATH.equals(request.getPath())) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (request.getMethod() != Request.METHOD_GET
                && request.getMethod() != Request.METHOD_DELETE
                && request.getMethod() != Request.METHOD_PUT) {
            session.sendError(Response.METHOD_NOT_ALLOWED, null);
            return;
        }

        String id = request.getParameter(ID_PARAMETER_KEY);
        if (id == null || id.isBlank()) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (request.getHeader(HEADER_REMOTE_ONE_NIO_HEADER) != null) {
            executorLocal.execute(() -> {
                try {
                    CompletableFuture<HandleResult> localFuture = local(request, id);
                    HandleResult local = localFuture.get();
                    Response response = new Response(String.valueOf(local.status()), local.data());
                    response.addHeader(HEADER_TIMESTAMP_ONE_NIO_HEADER + local.timestamp());
                    session.sendResponse(response);
                } catch (Exception e) {
                    ExceptionUtils.handleErrorFromHandleRequest(e, session);
                }
            });
            return;
        }

        int ack;
        int from;
        try {
            ack = getInt(request, ACK_PARAMETER_KEY, config.clusterUrls().size() / 2 + 1);
            from = getInt(request, FROM_PARAMETER_KEY, config.clusterUrls().size());
        } catch (IllegalArgumentException e) {
            session.sendError(UNPROCESSABLE_CONTENT_RESPONSE, "Invalid parameter values for ack/from");
            return;
        }

        if (from <= 0 || from > config.clusterUrls().size() || ack > from || ack <= 0) {
            session.sendError(Response.BAD_REQUEST, "Invalid parameter values for ack/from");
            return;
        }

        int[] indexes = getIndexes(id, from);
        MergeHandleResult mergeHandleResult = new MergeHandleResult(session, from, ack);
        for (int index : indexes) {
            String executorNode = config.clusterUrls().get(index);
            if (executorNode.equals(config.selfUrl())) {
                handleAsync(executorLocal, mergeHandleResult, () -> local(request, id));
            } else {
                handleAsync(executorRemote, mergeHandleResult, () -> remote(request, executorNode));
            }
        }
    }

    private int getInt(Request request, String param, int defaultValue) throws IllegalArgumentException {
        int intParameter;
        String parameterStr = request.getParameter(param);
        if (parameterStr == null || parameterStr.isBlank()) {
            intParameter = defaultValue;
        } else {
            try {
                intParameter = Integer.parseInt(parameterStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Failed to parse parameter '" + param + "' as an integer: " + parameterStr
                );
            }
        }
        return intParameter;
    }

    private CompletableFuture<HandleResult> remote(Request request, String executorNode) {
        try {
            return invokeRemote(executorNode, request);
        } catch (IOException e) {
            log.info("I/O exception while calling remote node", e);
            return wrapCompleted(new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Thread interrupted");
            return wrapCompleted(new HandleResult(HttpURLConnection.HTTP_UNAVAILABLE, Response.EMPTY));
        }

    }

    private void handleAsync(ExecutorService executor,
                             MergeHandleResult mergeHandleResult,
                             ExecutableTask runnable) {
        try {
            executor.execute(() -> {
                CompletableFuture<HandleResult> handleResult;
                try {
                    handleResult = runnable.run();
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    handleResult =
                            wrapCompleted(new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
                }

                mergeHandleResult.add(handleResult);
            });
        } catch (Exception e) {
            mergeHandleResult.add(
                    wrapCompleted(new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY))
            );
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

    private CompletableFuture<HandleResult> invokeRemote(String executorNode, Request request)
            throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header(HEADER_REMOTE, HEADER_REMOTE_VALUE)
                .timeout(Duration.ofMillis(500))
                .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(httpResponse -> {
                    Optional<String> timestampValue = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
                    long timestamp;
                    if (timestampValue.isPresent()) {
                        try {
                            timestamp = Long.parseLong(timestampValue.get());
                        } catch (Exception e) {
                            log.error("Failed to parse timestamp from header: {" + timestampValue.get() + "}");
                            timestamp = 0;
                        }
                    } else {
                        timestamp = 0;
                    }
                    return new HandleResult(httpResponse.statusCode(), httpResponse.body(), timestamp);
                })
                .exceptionally(e -> {
                    log.info("Exception while calling remote node: ", e);
                    return new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY);
                });
    }

    private CompletableFuture<HandleResult> local(Request request, String id) {
        long currentTimeMillis = System.currentTimeMillis();
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                ReferenceBaseEntry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    return wrapCompleted(new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY));
                }
                if (entry.value() == null) {
                    return wrapCompleted(
                            new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY, entry.timestamp())
                    );
                }

                return wrapCompleted(
                        new HandleResult(
                                HttpURLConnection.HTTP_OK,
                                entry.value().toArray(ValueLayout.JAVA_BYTE),
                                entry.timestamp()
                        )
                );
            }
            case Request.METHOD_PUT -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new ReferenceBaseEntry<>(key, value, currentTimeMillis));
                return wrapCompleted(new HandleResult(HttpURLConnection.HTTP_CREATED, Response.EMPTY));
            }
            case Request.METHOD_DELETE -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                dao.upsert(new ReferenceBaseEntry<>(key, null, currentTimeMillis));
                return wrapCompleted(new HandleResult(HttpURLConnection.HTTP_ACCEPTED, Response.EMPTY));
            }
            default -> {
                return wrapCompleted(new HandleResult(HttpURLConnection.HTTP_BAD_METHOD, Response.EMPTY));
            }
        }
    }

    /**
     * count <= config.clusterUrls().size()
     */
    private int[] getIndexes(String id, int count) {
        assert count < 5;

        int[] result = new int[count];
        int[] maxHashes = new int[count];

        for (int i = 0; i < count; i++) {
            String url = config.clusterUrls().get(i);
            int hash = Hash.murmur3(url + id);
            result[i] = i;
            maxHashes[i] = hash;
        }

        for (int i = count; i < config.clusterUrls().size(); i++) {
            String url = config.clusterUrls().get(i);
            int hash = Hash.murmur3(url + id);
            for (int j = 0; j < maxHashes.length; j++) {
                int maxHash = maxHashes[j];
                if (maxHash < hash) {
                    maxHashes[j] = hash;
                    result[j] = i;
                    break;
                }
            }
        }
        return result;
    }

    private CompletableFuture<HandleResult> wrapCompleted(HandleResult result) {
        return CompletableFuture.completedFuture(result);
    }

    private interface ExecutableTask {
        CompletableFuture<HandleResult> run() throws IOException;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        ReferenceService.shutdownAndAwaitTermination(executorLocal);
        ReferenceService.shutdownAndAwaitTermination(executorRemote);
    }
}
