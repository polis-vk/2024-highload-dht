package ru.vk.itmo.test.tarazanovmaxim;

import one.nio.http.HttpClient;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.tarazanovmaxim.dao.ReferenceDao;
import ru.vk.itmo.test.tarazanovmaxim.dao.TimestampBaseEntry;
import ru.vk.itmo.test.tarazanovmaxim.dao.TimestampEntry;
import ru.vk.itmo.test.tarazanovmaxim.hash.ConsistentHashing;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyServer extends HttpServer {

    private static final long FLUSH_THRESHOLD_BYTES = 1 << 20;
    private static final long REQUEST_TTL = TimeUnit.SECONDS.toNanos(1000000);
    private static final String PATH = "/v0/entity";
    private static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final String REDIRECT_HEADER = "Redirected: ";
    private static final String TIMESTAMP_HEADER = "Timestamp: ";
    private static final Logger logger = LoggerFactory.getLogger(MyServer.class);
    private static final int CODE_OK = 200;
    private static final int CODE_INTERNAL_ERROR = 500;
    private static final int RESPONSE_TIMEOUT = 500;
    private final ReferenceDao dao;
    private final ExecutorService executorService;
    private final Map<String, HttpClient> httpClients = new HashMap<>();
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

        int nodeCount = 1;
        HashSet<Integer> nodeSet = new HashSet<>(nodeCount);
        for (String url : config.clusterUrls()) {
            for (int i = 0; i < nodeCount; ++i) {
                nodeSet.add(Hash.murmur3(url));
            }
            shards.addShard(url, nodeSet);

            httpClients.put(url, new HttpClient(new ConnectionString(url)));
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

    public void close() throws IOException {
        executorService.shutdown();
        executorService.shutdownNow();

        for (HttpClient httpClient : httpClients.values()) {
            httpClient.close();
        }
        dao.close();
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
            response = httpClients.get(shard).invoke(request, RESPONSE_TIMEOUT);
        } catch (Exception e) {
            response = new Response(Response.BAD_GATEWAY, Response.EMPTY);
        }
        return response;
    }

    private Response getGoodGet(List<Response> responses) {
        List<Response> responses200 = new ArrayList<>();
        for (final var resp : responses) {
            if (resp.getStatus() == CODE_OK) {
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

    @Path(PATH)
    @RequestMethod(Request.METHOD_GET)
    public final Response get(@Param(value = "id", required = true) String id,
                              @Param(value = "ack") String ack,
                              @Param(value = "from") String from,
                              Request request) {
        int fromV = from == null ? clusterSize : Integer.parseInt(from);
        int ackV = ack == null ? quorum(fromV) : Integer.parseInt(ack);
        if (isParamsBad(id, ackV, fromV)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (request.getHeader(REDIRECT_HEADER) == null) {
            request.addHeader(REDIRECT_HEADER + "true");
            List<Response> responses = new ArrayList<>();
            List<String> shardToRequest = shards.getNShardByKey(id, fromV);
            for (String sendTo : shardToRequest) {
                Response answer = sendTo.equals(selfUrl) ? responseLocal(request, id) : shardLookup(request, sendTo);
                if (answer.getStatus() < CODE_INTERNAL_ERROR) {
                    responses.addLast(answer);
                }
            }
            if (responses.size() >= ackV) {
                return getGoodGet(responses);
            }
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
        return responseLocal(request, id);
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_PUT)
    public final Response put(@Param(value = "id", required = true) String id,
                              @Param(value = "ack") String ack,
                              @Param(value = "from") String from,
                              Request request) {
        int fromV = from == null ? clusterSize : Integer.parseInt(from);
        int ackV = ack == null ? quorum(fromV) : Integer.parseInt(ack);
        if (isParamsBad(id, ackV, fromV)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (request.getHeader(REDIRECT_HEADER) == null) {
            request.addHeader(REDIRECT_HEADER + "true");
            List<Response> responses = new ArrayList<>();
            List<String> shardToRequest = shards.getNShardByKey(id, ackV);
            for (String sendTo : shardToRequest) {
                Response answer = sendTo.equals(selfUrl) ? responseLocal(request, id) : shardLookup(request, sendTo);
                if (answer.getStatus() < CODE_INTERNAL_ERROR) {
                    responses.addLast(answer);
                }
                if (responses.size() == ackV) {
                    return responses.getFirst();
                }
            }
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
        return responseLocal(request, id);
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public final Response delete(@Param(value = "id", required = true) String id,
                                 @Param(value = "ack") String ack,
                                 @Param(value = "from") String from,
                                 Request request) {
        int fromV = from == null ? clusterSize : Integer.parseInt(from);
        int ackV = ack == null ? quorum(fromV) : Integer.parseInt(ack);
        if (isParamsBad(id, ackV, fromV)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (request.getHeader(REDIRECT_HEADER) == null) {
            request.addHeader(REDIRECT_HEADER + "true");
            List<Response> responses = new ArrayList<>();
            List<String> shardToRequest = shards.getNShardByKey(id, ackV);
            for (String sendTo : shardToRequest) {
                Response answer = sendTo.equals(selfUrl) ? responseLocal(request, id) : shardLookup(request, sendTo);
                if (answer.getStatus() < CODE_INTERNAL_ERROR) {
                    responses.addLast(answer);
                }
                if (responses.size() == ackV) {
                    return responses.getFirst();
                }
            }
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
        return responseLocal(request, id);
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
                    logger.error("IOException in handleRequest->executorService.execute(): {} M{}",
                            e, request.getMethod());
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
            logger.error("RejectedExecutionException in handleRequest: {} {}", request, session);
            sendResponse(new Response(TOO_MANY_REQUESTS, Response.EMPTY), session);
        }
    }

    public void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("IOException in sendResponse: {} {}", response, session);
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
