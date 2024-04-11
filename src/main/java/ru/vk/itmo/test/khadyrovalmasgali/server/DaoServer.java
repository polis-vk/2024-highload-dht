package ru.vk.itmo.test.khadyrovalmasgali.server;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.khadyrovalmasgali.dao.TimestampEntry;
import ru.vk.itmo.test.khadyrovalmasgali.hashing.Node;
import ru.vk.itmo.test.khadyrovalmasgali.replication.HandleResult;
import ru.vk.itmo.test.khadyrovalmasgali.replication.MergeHandleResult;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DaoServer extends HttpServer {

    private static final String HEADER_REMOTE = "X-flag-remote-reference-server-to-node-by-paschenko";
    private static final String HEADER_REMOTE_ONE_NIO_HEADER = HEADER_REMOTE + ": da";
    private static final String HEADER_TIMESTAMP = "X-flag-remote-reference-server-to-node-by-paschenko2";
    private static final String HEADER_TIMESTAMP_ONE_NIO_HEADER = HEADER_TIMESTAMP + ": ";
    private static final Logger log = LoggerFactory.getLogger(DaoServer.class);
    private static final String ENTITY_PATH = "/v0/entity";
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final int Q_SIZE = 512;
    private static final int KEEP_ALIVE_TIME = 50;
    private static final int TERMINATION_TIMEOUT = 60;
    private static final int HTTP_CLIENT_TIMEOUT_MS = 1000;

    private final ServiceConfig config;
    private final ExecutorService executorService = new ThreadPoolExecutor(
            THREADS,
            THREADS,
            KEEP_ALIVE_TIME,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Q_SIZE),
            new ThreadPoolExecutor.AbortPolicy());
    private final List<Node> nodes;
    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;
    private final HttpClient httpClient;

    public DaoServer(ServiceConfig config, Dao<MemorySegment, TimestampEntry<MemorySegment>> dao) throws IOException {
        super(createHttpServerConfig(config));
        this.config = config;
        this.dao = dao;
        nodes = new ArrayList<>();
        for (String url : config.clusterUrls()) {
            nodes.add(new Node(url));
        }
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(HTTP_CLIENT_TIMEOUT_MS))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (!ENTITY_PATH.equals(request.getPath())) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (request.getMethod() != Request.METHOD_GET
                && request.getMethod() != Request.METHOD_DELETE
                && request.getMethod() != Request.METHOD_PUT) {
            session.sendError(Response.METHOD_NOT_ALLOWED, null);
            return;
        }

        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (request.getHeader(HEADER_REMOTE_ONE_NIO_HEADER) != null) {
            processLocal(request, session, id);
            return;
        }

        int ack = getParam(request, "ack=", config.clusterUrls().size() / 2 + 1);
        int from = getParam(request, "from=", config.clusterUrls().size());

        if (from <= 0 || from > config.clusterUrls().size() || ack > from || ack <= 0) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        int[] indexes = determineResponsibleNodes(id, from);
        MergeHandleResult mergeResult = new MergeHandleResult(indexes.length, ack, session);
        for (int i = 0; i < indexes.length; ++i) {
            Node node = nodes.get(indexes[i]);
            if (!config.selfUrl().equals(node.getUrl())) {
                handleAsync(mergeResult, i, () -> remote(request, node));
            } else {
                handleAsync(mergeResult, i, () -> local(request, id));
            }
        }
    }

    private void processLocal(Request request, HttpSession session, String id) {
        executorService.execute(() -> {
            try {
                HandleResult local = local(request, id);
                Response response = new Response(String.valueOf(local.status()), local.data());
                response.addHeader(HEADER_TIMESTAMP_ONE_NIO_HEADER + local.timestamp());
                session.sendResponse(response);
            } catch (Exception e) {
                log.error("Exception during handleRequest", e);
                try {
                    session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                } catch (IOException ex) {
                    log.error("Exception while sending close connection", e);
                    session.scheduleClose();
                }
            }
        });
    }

    private HandleResult remote(Request request, Node node) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(node.getUrl() + request.getURI()))
                    .method(
                            request.getMethodName(),
                            request.getBody() == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                    )
                    .header(HEADER_REMOTE, "da")
                    .timeout(Duration.ofMillis(HTTP_CLIENT_TIMEOUT_MS))
                    .build();
            HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            Optional<String> string = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
            long timestamp;
            timestamp = string.map(Long::parseLong).orElse(0L);
            return new HandleResult(httpResponse.statusCode(), httpResponse.body(), timestamp);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error(e.getMessage());
            return new HandleResult(HttpURLConnection.HTTP_UNAVAILABLE, Response.EMPTY);
        } catch (IOException e) {
            return new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private HandleResult local(Request request, String id) {
        long timestamp = System.currentTimeMillis();
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                TimestampEntry<MemorySegment> entry = dao.get(stringToMemorySegment(id));
                if (entry == null) {
                    return new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY);
                }
                if (entry.value() == null) {
                    return new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY, entry.timestamp());
                }
                return new HandleResult(
                        HttpURLConnection.HTTP_OK,
                        entry.value().toArray(ValueLayout.JAVA_BYTE),
                        entry.timestamp());
            }
            case Request.METHOD_PUT -> {
                dao.upsert(new TimestampEntry<>(
                        stringToMemorySegment(id),
                        MemorySegment.ofArray(request.getBody()),
                        timestamp));
                return new HandleResult(HttpURLConnection.HTTP_CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                dao.upsert(new TimestampEntry<>(stringToMemorySegment(id), null, timestamp));
                return new HandleResult(HttpURLConnection.HTTP_ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new HandleResult(HttpURLConnection.HTTP_BAD_METHOD, Response.EMPTY);
            }
        }
    }

    private void handleAsync(MergeHandleResult mergeResult, int index, ERunnable r) {
        try {
            executorService.execute(() -> {
                try {
                    HandleResult result = r.run();
                    mergeResult.add(result, index);
                } catch (Exception e) {
                    if (e instanceof HttpException) {
                        log.error(e.getMessage());
                        mergeResult.add(new HandleResult(HttpURLConnection.HTTP_BAD_REQUEST, Response.EMPTY), index);
                    } else {
                        log.error(e.getMessage());
                        mergeResult.add(new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY), index);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("too many requests", e);
            mergeResult.add(new HandleResult(HttpURLConnection.HTTP_UNAVAILABLE, Response.EMPTY), index);
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        shutdownAndAwaitTermination(executorService);
    }

    private int getParam(Request request, String p, int defaultValue) {
        String param = request.getParameter(p);
        int result;
        if (param == null || param.isBlank()) {
            result = defaultValue;
        } else {
            try {
                result = Integer.parseInt(param);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("parse error");
            }
        }
        return result;
    }

    private int[] determineResponsibleNodes(String key, int count) {
        int[] result = new int[count];
        int[] scores = new int[count];
        for (int i = 0; i < count; ++i) {
            int score = nodes.get(i).computeScore(key);
            scores[i] = score;
            result[i] = i;
        }
        for (int i = count; i < nodes.size(); ++i) {
            int score = nodes.get(i).computeScore(key);
            for (int j = 0; j < count; ++j) {
                if (scores[j] < score) {
                    scores[j] = score;
                    result[j] = i;
                    break;
                }
            }
        }
        return result;
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS)) {
                    log.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static MemorySegment stringToMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private interface ERunnable {
        HandleResult run() throws IOException;
    }
}
