package ru.vk.itmo.test.tarazanovmaxim;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.server.SelectorThread;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.tarazanovmaxim.dao.ReferenceDao;
import ru.vk.itmo.test.tarazanovmaxim.dao.TimestampBaseEntry;
import ru.vk.itmo.test.tarazanovmaxim.dao.TimestampEntry;
import ru.vk.itmo.test.tarazanovmaxim.hash.ConsistentHashing;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MyServer extends HttpServer {

    private static final long FLUSH_THRESHOLD_BYTES = 1 << 20;
    private static final long REQUEST_TTL = TimeUnit.SECONDS.toNanos(1000000);
    private static final String PATH = "/v0/entity";
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String REDIRECT_HEADER = "Redirected: ";
    private static final String TIMESTAMP_HEADER = "Timestamp: ";
    private static final Logger logger = LoggerFactory.getLogger(MyServer.class);
    private final ReferenceDao dao;
    private final ExecutorService executorService;
    private final HttpClient client;
    private final ConsistentHashing shards = new ConsistentHashing();
    private final int clusterSize;
    private final String selfUrl;

    public MyServer(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        selfUrl = config.selfUrl();
        clusterSize = config.clusterUrls().size();
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));

        int corePoolSize = Runtime.getRuntime().availableProcessors() / 2 + 1;
        long keepAliveTime = 0L;
        int queueCapacity = 100;

        executorService = new ThreadPoolExecutor(
                corePoolSize,
                corePoolSize,
                keepAliveTime,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );

        client = HttpClient.newBuilder()
                .executor(executorService)
                .build();

        int nodeCount = 1;
        HashSet<Integer> nodeSet = new HashSet<>(nodeCount);
        for (String url : config.clusterUrls()) {
            for (int i = 0; i < nodeCount; ++i) {
                nodeSet.add(Hash.murmur3(url));
            }
            shards.addShard(url, nodeSet);
        }
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();

        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;

        return httpServerConfig;
    }

    private static MemorySegment toMemorySegment(String string) {
        return MemorySegment.ofArray(string.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized Dao getDao() {
        return dao;
    }

    @Override
    public synchronized void stop() {
        executorService.shutdown();
        try {
            dao.close();
        } catch (IOException e) {
            logger.error("dao.close() -> exception()");
        }
        super.stop();
        executorService.shutdownNow();
    }

    private int quorum(final int from) {
        return from / 2 + 1;
    }

    private boolean isParamsBad(String id, int ack, int from) {
        return id == null || id.isEmpty() || ack <= 0
                || from > clusterSize || ack > from;
    }

    private Response shardLookup(final Request request, final String shard) {
        Response response;
        try {
            response = convertResponse(
                client.send(
                    convertRequest(request, shard),
                    HttpResponse.BodyHandlers.ofByteArray()
                )
            );
        } catch (IOException e) {
            response = new Response(Response.BAD_GATEWAY, Response.EMPTY);
        } catch (InterruptedException e) {
            response = new Response(Response.BAD_GATEWAY, Response.EMPTY);
            Thread.currentThread().interrupt();
        }
        return response;
    }

    private static HttpRequest convertRequest(Request request, String url) {
        return HttpRequest.newBuilder(URI.create(url + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header("Redirected", "true")
                .build();
    }

    private static Response convertResponse(HttpResponse<byte[]> response) {
        String status = switch (response.statusCode()) {
            case HttpURLConnection.HTTP_OK -> Response.OK;
            case HttpURLConnection.HTTP_CREATED -> Response.CREATED;
            case HttpURLConnection.HTTP_ACCEPTED -> Response.ACCEPTED;
            case HttpURLConnection.HTTP_BAD_REQUEST -> Response.BAD_REQUEST;
            case HttpURLConnection.HTTP_NOT_FOUND -> Response.NOT_FOUND;
            case HttpURLConnection.HTTP_INTERNAL_ERROR -> Response.INTERNAL_ERROR;
            case HttpURLConnection.HTTP_NOT_IMPLEMENTED -> Response.NOT_IMPLEMENTED;
            case HttpURLConnection.HTTP_BAD_GATEWAY -> Response.BAD_GATEWAY;
            case HttpURLConnection.HTTP_GATEWAY_TIMEOUT -> Response.GATEWAY_TIMEOUT;
            default -> throw new IllegalArgumentException(response.statusCode() + "Unknown status code");
        };
        Response convertedResponse = new Response(status, response.body());
        response.headers().firstValue("Timestamp").ifPresent((timestamp) ->
                convertedResponse.addHeader(TIMESTAMP_HEADER + timestamp)
        );
        return convertedResponse;
    }

    private Response getGoodGet(final List<Response> responses) {
        List<Response> responses200 = new ArrayList<>();
        for (final var resp : responses) {
            if (resp.getStatus() == 200) {
                responses200.addLast(resp);
            }
        }

        if (responses200.isEmpty()) {
            return responses.getFirst();
        }

        Response tombstone = Collections.min(
            responses,
            Comparator.comparingLong(
                    response -> Long.parseLong(response.getHeader(TIMESTAMP_HEADER))
            )
        );

        Response latest200 = Collections.max(
            responses200,
            Comparator.comparingLong(
                    response -> Long.parseLong(response.getHeader(TIMESTAMP_HEADER))
            )
        );

        long tsTombstone = -Long.parseLong(tombstone.getHeader(TIMESTAMP_HEADER));
        long tsLatest200 = Long.parseLong(latest200.getHeader(TIMESTAMP_HEADER));

        return tsTombstone > tsLatest200 ? tombstone : latest200;
    }

    private Response supplyAsync(final String sendTo, final Request request, final String id) {
        if (sendTo.equals(selfUrl)) {
            return responseLocal(request, id);
        } else {
            return shardLookup(request, sendTo);
        }
    }

    private void actionOnResponse(
            final Response response,
            final Throwable throwable,
            final List<Response> responses,
            final AtomicInteger fails) {
        if (throwable == null) {
            if (response.getStatus() < 500) {
                responses.addLast(response);
            } else {
                fails.incrementAndGet();
            }
        } else {
            fails.incrementAndGet();
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Path(PATH)
    @RequestMethod(Request.METHOD_GET)
    public final void get(@Param(value = "id", required = true) String id,
                          @Param(value = "ack") String ack,
                          @Param(value = "from") String from,
                          Request request,
                          HttpSession session) {
        int fromV = from == null ? clusterSize : Integer.parseInt(from);
        int ackV = ack == null ? quorum(fromV) : Integer.parseInt(ack);
        if (isParamsBad(id, ackV, fromV)) {
            sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
            return;
        }

        if (request.getHeader(REDIRECT_HEADER) != null) {
            sendResponse(responseLocal(request, id), session);
            return;
        }

        request.addHeader(REDIRECT_HEADER + "true");
        List<Response> responses = new CopyOnWriteArrayList<>();
        List<String> shardToRequest = shards.getNShardByKey(id, fromV);
        AtomicInteger fails = new AtomicInteger(0);
        AtomicBoolean sent = new AtomicBoolean(false);
        for (String sendTo : shardToRequest) {
            CompletableFuture
                .supplyAsync(() -> supplyAsync(sendTo, request, id), executorService)
                //.completeOnTimeout(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY), 1, TimeUnit.SECONDS)
                .whenCompleteAsync((response, throwable) -> {
                    actionOnResponse(response, throwable, responses, fails);
                    if (responses.size() >= ackV && sent.compareAndSet(false, true)) {
                        sendResponse(getGoodGet(responses), session);
                        return;
                    }

                    if (fails.get() > fromV - ackV && sent.compareAndSet(false, true)) {
                        sendResponse(new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY), session);
                    }
                }, executorService);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Path(PATH)
    @RequestMethod(Request.METHOD_PUT)
    public final void put(@Param(value = "id", required = true) String id,
                          @Param(value = "ack") String ack,
                          @Param(value = "from") String from,
                          Request request,
                          HttpSession session) {
        int fromV = from == null ? clusterSize : Integer.parseInt(from);
        int ackV = ack == null ? quorum(fromV) : Integer.parseInt(ack);
        if (isParamsBad(id, ackV, fromV)) {
            sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
            return;
        }
        if (request.getHeader(REDIRECT_HEADER) != null) {
            sendResponse(responseLocal(request, id), session);
            return;
        }
        List<Response> responses = new CopyOnWriteArrayList<>();
        List<String> shardToRequest = shards.getNShardByKey(id, fromV);
        AtomicInteger fails = new AtomicInteger(0);
        AtomicBoolean sent = new AtomicBoolean(false);
        for (String sendTo : shardToRequest) {
            CompletableFuture
                .supplyAsync(() -> supplyAsync(sendTo, request, id), executorService)
                //.completeOnTimeout(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY), 1, TimeUnit.SECONDS)
                .whenCompleteAsync((response, throwable) -> {
                    actionOnResponse(response, throwable, responses, fails);
                    if (responses.size() >= ackV && sent.compareAndSet(false, true)) {
                        sendResponse(responses.getFirst(), session);
                        return;
                    }

                    if (fails.get() > fromV - ackV && sent.compareAndSet(false, true)) {
                        sendResponse(new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY), session);
                    }
                }, executorService);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Path(PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public final void delete(@Param(value = "id", required = true) String id,
                             @Param(value = "ack") String ack,
                             @Param(value = "from") String from,
                             Request request,
                             HttpSession session) {
        int fromV = from == null ? clusterSize : Integer.parseInt(from);
        int ackV = ack == null ? quorum(fromV) : Integer.parseInt(ack);
        if (isParamsBad(id, ackV, fromV)) {
            sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
            return;
        }

        if (request.getHeader(REDIRECT_HEADER) != null) {
            sendResponse(responseLocal(request, id), session);
            return;
        }
        request.addHeader(REDIRECT_HEADER + "true");
        List<Response> responses = new CopyOnWriteArrayList<>();
        List<String> shardToRequest = shards.getNShardByKey(id, fromV);
        AtomicInteger fails = new AtomicInteger(0);
        AtomicBoolean sent = new AtomicBoolean(false);
        for (String sendTo : shardToRequest) {
            CompletableFuture
                .supplyAsync(() -> supplyAsync(sendTo, request, id), executorService)
                //.completeOnTimeout(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY), 1, TimeUnit.SECONDS)
                .whenCompleteAsync((response, throwable) -> {
                    actionOnResponse(response, throwable, responses, fails);
                    if (responses.size() >= ackV && sent.compareAndSet(false, true)) {
                        sendResponse(responses.getFirst(), session);
                        return;
                    }
                    if (fails.get() > fromV - ackV && sent.compareAndSet(false, true)) {
                        sendResponse(new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY), session);
                    }
                }, executorService);
        }
    }

    @Path(PATH)
    public Response otherMethod() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        sendResponse(response, session);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        try {
            long startTime = System.nanoTime();
            executorService.execute(() -> {
                if (System.nanoTime() > startTime + REQUEST_TTL) {
                    sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY), session);
                    return;
                }

                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    logger.error("IOException in handleRequest->executorService.execute(): "
                            + e + " M" + request.getMethod());
                    sendResponse(
                            new Response(
                                    e.getClass() == IOException.class ? Response.INTERNAL_ERROR : Response.BAD_REQUEST,
                                    Response.EMPTY
                            ),
                            session
                    );
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("RejectedExecutionException in handleRequest: " + request + session);
            sendResponse(new Response(TOO_MANY_REQUESTS, Response.EMPTY), session);
        }
    }

    public void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("IOException in sendResponse: " + response + session);
        }
    }

    private Response responseLocal(Request request, String id) {
        String timestampFromHeader = request.getHeader(TIMESTAMP_HEADER);
        long timestamp = timestampFromHeader == null ? System.currentTimeMillis() : Long.parseLong(timestampFromHeader);
        MemorySegment key = toMemorySegment(id);
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                TimestampEntry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                    response.addHeader(TIMESTAMP_HEADER + timestamp);
                    return response;
                }

                Response response = (entry.value() == null)
                        ? new Response(Response.NOT_FOUND, Response.EMPTY)
                        : new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
                response.addHeader(TIMESTAMP_HEADER + entry.timestamp());
                return response;
            }
            case Request.METHOD_PUT -> {
                TimestampEntry<MemorySegment> entry = new TimestampBaseEntry<>(
                        key,
                        MemorySegment.ofArray(request.getBody()),
                        timestamp
                );
                dao.upsert(entry);
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                TimestampEntry<MemorySegment> entry = new TimestampBaseEntry<>(key, null, -timestamp);
                dao.upsert(entry);
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }
}
