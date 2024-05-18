package ru.vk.itmo.test.osipovdaniil;

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
import ru.vk.itmo.test.osipovdaniil.dao.ReferenceBaseEntry;
import ru.vk.itmo.test.osipovdaniil.dao.ReferenceDao;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerImpl extends HttpServer {

    public static final String ENTITY_PATH = "/v0/entity";
    public static final String START = "start=";
    private static final String HEADER_REMOTE = "X-flag-remote-server-to-node";
    private static final String HEADER_REMOTE_ONE_NIO_HEADER = HEADER_REMOTE + ": true";
    private static final String HEADER_TIMESTAMP = "X-flag-remote-server-to-node";
    private static final String HEADER_TIMESTAMP_ONE_NIO_HEADER = HEADER_TIMESTAMP + ": ";
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    public static final String ID = "id=";
    public static final String ACK = "ack=";
    public static final String FROM = "from=";
    public static final String ENTITIES_PATH = "/v0/entities";
    public static final String END = "end=";
    private static final byte[] CHUNK_HEADERS =
            """
            HTTP/1.1 200\r
            OKContentType: application/octet-stream\r
            Transfer-Encoding: chunked\r
            Connection: keep-alive\r
            \r
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] N_SEP = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CHUNK_SEP = "\r\n".getBytes(StandardCharsets.UTF_8);

    private final ExecutorService executorLocal = Executors.newFixedThreadPool(THREADS / 2,
            new CustomThreadFactory("local-work"));
    private final ExecutorService executorRemote = Executors.newFixedThreadPool(THREADS / 2,
            new CustomThreadFactory("remote-work"));
    private final ReferenceDao dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public ServerImpl(final ServiceConfig config,
                      final ReferenceDao dao) throws IOException {
        super(createServerConfigWithPort(config.selfPort()));
        this.dao = dao;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static HttpServerConfig createServerConfigWithPort(final int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        serverConfig.selectors = Runtime.getRuntime().availableProcessors() / 2;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private boolean validateId(final String id) {
        return id != null && !id.isEmpty();
    }

    private boolean validateMethod(final int method) {
        return method == Request.METHOD_PUT || method == Request.METHOD_DELETE || method == Request.METHOD_GET;
    }

    private boolean validateAckFrom(final int ack, final int from) {
        return from > 0 && from <= config.clusterUrls().size() && ack <= from && ack > 0;
    }

    @Override
    public void handleRequest(final Request request, final HttpSession session) throws IOException {
        final String path = request.getPath();
        if (path.startsWith(ENTITIES_PATH)) {
            getRange(request, session);
            return;
        }
        if (!ENTITY_PATH.equals(path)) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }
        if (!validateMethod(request.getMethod())) {
            session.sendError(Response.METHOD_NOT_ALLOWED, null);
            return;
        }
        final String id = request.getParameter(ID);
        if (!validateId(id)) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }
        if (request.getHeader(HEADER_REMOTE_ONE_NIO_HEADER) != null) {
            executorLocal.execute(() -> {
                try {
                    final HandleResult local = local(request, id);
                    final Response response = new Response(String.valueOf(local.status()), local.data());
                    response.addHeader(HEADER_TIMESTAMP_ONE_NIO_HEADER + local.timestamp());
                    session.sendResponse(response);
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    Utils.sendEmptyInternal(session, log);
                }
            });
            return;
        }
        final int ack = getInt(request, ACK, config.clusterUrls().size() / 2 + 1);
        final int from = getInt(request, FROM, config.clusterUrls().size());
        if (!validateAckFrom(ack, from)) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }
        final int[] indexes = getIndexes(id, from);
        final MergeHandleResult mergeHandleResult = new MergeHandleResult(session, indexes.length, ack);
        for (int i = 0; i < indexes.length; i++) {
            int index = indexes[i];
            final String executorNode = config.clusterUrls().get(index);
            if (executorNode.equals(config.selfUrl())) {
                handleAsync(executorLocal, i, mergeHandleResult, () -> localAsync(request, id));
            } else {
                handleAsync(executorRemote, i, mergeHandleResult, () -> remoteAsync(request, executorNode));
            }
        }

    }

    private void getRange(final Request request, final HttpSession session) throws IOException {
        final String startParameter = request.getParameter(START);
        final String endParameter = request.getParameter(END);
        if (!validateStartEnd(startParameter, endParameter)) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }
        final MemorySegment startKeySegment = MemorySegment.ofArray(startParameter.getBytes(StandardCharsets.UTF_8));
        final MemorySegment endKeySegment = endParameter == null
                ? null : MemorySegment.ofArray(endParameter.getBytes(StandardCharsets.UTF_8));
        final Iterator<ReferenceBaseEntry<MemorySegment>> iterator = dao.get(startKeySegment, endKeySegment);
        writeChunkRes(iterator, session);
    }

    private void writeChunkRes(final Iterator<ReferenceBaseEntry<MemorySegment>> iterator, final HttpSession session)
            throws IOException {
        if (!iterator.hasNext()) {
            session.sendResponse(new Response(Response.OK, Response.EMPTY));
            return;
        }
        session.write(CHUNK_HEADERS, 0, CHUNK_HEADERS.length);
        while (iterator.hasNext()) {
            writeChunkEntry(iterator.next(), session);
        }
        final byte[] zero = Integer.toHexString(0).toUpperCase().getBytes(StandardCharsets.UTF_8);
        session.write(zero, 0, zero.length);
        session.write(CHUNK_SEP, 0, CHUNK_SEP.length);
        session.write(CHUNK_SEP, 0, CHUNK_SEP.length);
        session.close();
    }

    private void writeChunkEntry(final ReferenceBaseEntry<MemorySegment> entry, final HttpSession session)
            throws IOException {
        final byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
        final byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);
        final byte[] length = Integer.toHexString(key.length + value.length + N_SEP.length)
                .toUpperCase()
                .getBytes(StandardCharsets.UTF_8);
        session.write(length, 0, length.length);
        session.write(CHUNK_SEP, 0, CHUNK_SEP.length);
        session.write(key, 0, key.length);
        session.write(N_SEP, 0, N_SEP.length);
        session.write(value, 0, value.length);
        session.write(CHUNK_SEP, 0, CHUNK_SEP.length);
    }

    private boolean validateStartEnd(final String startParameter, final String endParameter) {
        return startParameter != null && !startParameter.isEmpty() && (endParameter == null || !endParameter.isEmpty());
    }

    private int getInt(final Request request, final String param, final int defaultValue) {
        int ack;
        String ackStr = request.getParameter(param);
        if (ackStr == null || ackStr.isBlank()) {
            ack = defaultValue;
        } else {
            try {
                ack = Integer.parseInt(ackStr);
            } catch (Exception e) {
                throw new IllegalArgumentException("parse error (not int)");
            }
        }
        return ack;
    }

    private CompletableFuture<HandleResult> remoteAsync(final Request request, final String executorNode) {
        return CompletableFuture.supplyAsync(() -> remote(request, executorNode));
    }

    private HandleResult remote(final Request request, final String executorNode) {
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

    private void handleAsync(final ExecutorService executor,
                             final int index,
                             final MergeHandleResult mergeHandleResult,
                             final ERunnable runnable) {
        final AtomicBoolean done = new AtomicBoolean();

        CompletableFuture<HandleResult> completableFuture;
        try {
            completableFuture = runnable.run();
        } catch (Exception e) {
            log.error("Exception during handleRequest", e);
            completableFuture = CompletableFuture.supplyAsync(
                    () -> new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
        }
        completableFuture.whenCompleteAsync((handleResult, throwable) -> {
            if (done.get()) {
                return;
            }
            // throwable != null
            done.set(mergeHandleResult.add(index, handleResult));
        }, executor).exceptionally(
                throwable -> new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
    }

    @Override
    public void handleDefault(final Request request, final HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    private HandleResult invokeRemote(final String executorNode, final Request request)
            throws IOException, InterruptedException {
        final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header(HEADER_REMOTE, "true")
                .timeout(Duration.ofMillis(500))
                .build();
        final HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        final Optional<String> string = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
        long timestamp;
        if (string.isPresent()) {
            try {
                timestamp = Long.parseLong(string.get());
            } catch (Exception e) {
                log.error("error timestamp parsing\n" + e);
                timestamp = 0;
            }
        } else {
            timestamp = 0;
        }
        return new HandleResult(httpResponse.statusCode(), httpResponse.body(), timestamp);
    }

    private CompletableFuture<HandleResult> localAsync(final Request request, final String id) {
        return CompletableFuture.supplyAsync(() -> local(request, id));
    }

    private HandleResult local(final Request request, final String id) {
        final long currentTimeMillis = System.currentTimeMillis();
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                final MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                final ReferenceBaseEntry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    return new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY);
                }
                if (entry.value() == null) {
                    return new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY, entry.timestamp());
                }
                return new HandleResult(HttpURLConnection.HTTP_OK,
                        entry.value().toArray(ValueLayout.JAVA_BYTE), entry.timestamp());
            }
            case Request.METHOD_PUT -> {
                final MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                final MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new ReferenceBaseEntry<>(key, value, currentTimeMillis));
                return new HandleResult(HttpURLConnection.HTTP_CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                final MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                dao.upsert(new ReferenceBaseEntry<>(key, null, currentTimeMillis));
                return new HandleResult(HttpURLConnection.HTTP_ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new HandleResult(HttpURLConnection.HTTP_BAD_METHOD, Response.EMPTY);
            }
        }
    }

    // count <= config.clusterUrls().size()
    private int[] getIndexes(final String id, final int count) {
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
        CompletableFuture<HandleResult> run() throws IOException;
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        try {
            if (!pool.awaitTermination(60, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(60, TimeUnit.MILLISECONDS)) {
                    log.info("Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        shutdownAndAwaitTermination(executorLocal);
        shutdownAndAwaitTermination(executorRemote);
    }
}
