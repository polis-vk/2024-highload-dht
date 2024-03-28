package ru.vk.itmo.test.alexeyshemetov;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.PathMapper;
import one.nio.http.Request;
import one.nio.http.RequestHandler;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.alexeyshemetov.dao.ReferenceDao;
import ru.vk.itmo.test.alexeyshemetov.sharding.ConsistentHashing;
import ru.vk.itmo.test.alexeyshemetov.sharding.ShardingManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SuppressWarnings("unused")
public class Server extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String ENTITY_PATH = "/v0/entity";
    private static final long MEM_DAO_SIZE = 1L << 21;
    private static final int KEEP_ALIVE_THREAD_SECONDS = 30;
    private static final int AWAIT_TERMINATION_TIME_MINUTES = 5;
    private static final int QUEUE_CAPACITY = 1024;
    private static final int WAIT_RESPONSE_SECONDS = 60;
    private final ExecutorService executorService;
    private final PathMapper pathMapper;
    private final HttpClient client;
    private final ShardingManager shardingManager;
    private final String selfUrl;

    public Server(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        try {
            dao = new ReferenceDao(new Config(config.workingDir(), MEM_DAO_SIZE));
        } catch (IOException e) {
            throw new UncheckedIOException("Can't start server", e);
        }
        executorService = createExecutorService();
        pathMapper = createPathMapper();
        client = HttpClient.newHttpClient();
        shardingManager = createShardingManager(config);
        selfUrl = config.selfUrl();
    }

    @Path(ENTITY_PATH)
    public Response entityById(
        @Param(value = "id", required = true) final String id,
        Request request
    ) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        return switch (request.getMethod()) {
            case Request.METHOD_GET -> getById(id);
            case Request.METHOD_PUT -> putById(id, request);
            case Request.METHOD_DELETE -> deleteById(id);
            default -> notAllowed();
        };
    }

    public Response getById(final String id) {
        MemorySegment key = toMemorySegment(id);
        Entry<MemorySegment> segmentEntry = dao.get(key);
        if (segmentEntry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(segmentEntry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response putById(final String id, Request request) {
        MemorySegment key = toMemorySegment(id);
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response deleteById(final String id) {
        MemorySegment key = toMemorySegment(id);
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    public Response notAllowed() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            Future<?> submit = executorService.submit(() -> {
                try {
                    handleRequestWrapper(request, session);
                } catch (Exception e) {
                    log.error("Exception occurred while handling request", e);
                    sendResponse(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Can not schedule task for execution", e);
            sendResponse(session, new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    private PathMapper createPathMapper() {
        PathMapper mapper = new PathMapper();
        mapper.add(ENTITY_PATH, null, this::handleRequest);
        return mapper;
    }

    private void handleRequestWrapper(Request request, HttpSession session) {
        RequestHandler handler = pathMapper.find(request.getPath(), request.getMethod());
        if (handler == null) {
            handleDefault(request, session);
            return;
        }
        String id = request.getParameter("id=");
        if (id == null) {
            sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }
        log.info("handling {} for id {}", request.getMethodName(), id);

        String clusterUrl = shardingManager.getClusterUrlByKey(id);

        if (clusterUrl.equals(selfUrl)) {
            sendResponse(session, entityById(id, request));
        } else {
            handleRequestProxy(clusterUrl, request, session);
        }
    }

    private void handleRequestProxy(String clusterUrl, Request request, HttpSession session) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(clusterUrl + request.getURI()));
        HttpRequest.BodyPublisher bodyPublishers = request.getBody() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(request.getBody());
        builder.method(request.getMethodName(), bodyPublishers);

        try {
            CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(
                builder.build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            HttpResponse<byte[]> httpResponse;
            httpResponse = future.get(WAIT_RESPONSE_SECONDS, TimeUnit.SECONDS);
            var response = new Response(Utils.statusCodeToResponseCode(httpResponse.statusCode()), httpResponse.body());
            sendResponse(session, response);
        } catch (ExecutionException e) {
            log.error("can not reach {}", clusterUrl, e);
            sendResponse(session, new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        } catch (InterruptedException e) {
            log.error("error occurred while handling http response", e);
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            log.error("time out\nurl: {}\nrequest: {}\nsession: {}", clusterUrl, request, session);
        }
    }

    private void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            log.error("failed to send response: {} to session: {}", response, session, e);
            session.close();
        }
    }

    @Override
    public synchronized void stop() {
        try {
            super.stop();
        } catch (Exception e) {
            log.error("Error while closing server", e);
        }
        shutdownAwait(executorService);
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Error while stopping server", e);
        }
    }

    private static void shutdownAwait(ExecutorService executorService) {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(AWAIT_TERMINATION_TIME_MINUTES, TimeUnit.MINUTES)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService createExecutorService() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            cores / 2,
            cores,
            KEEP_ALIVE_THREAD_SECONDS,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    private static MemorySegment toMemorySegment(String string) {
        return MemorySegment.ofArray(string.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static ShardingManager createShardingManager(ServiceConfig config) {
        return new ConsistentHashing(config.clusterUrls(), 50, Hash::murmur3);
    }
}
