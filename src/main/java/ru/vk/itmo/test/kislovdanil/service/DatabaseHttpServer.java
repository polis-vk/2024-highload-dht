package ru.vk.itmo.test.kislovdanil.service;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kislovdanil.dao.BaseEntry;
import ru.vk.itmo.test.kislovdanil.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.PersistentDao;
import ru.vk.itmo.test.kislovdanil.service.sharding.RequestsManager;
import ru.vk.itmo.test.kislovdanil.service.sharding.Sharder;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DatabaseHttpServer extends HttpServer {
    private final PersistentDao dao;
    private final Sharder sharder;
    private static final String ENTITY_ACCESS_URL = "/v0/entity";
    private static final String ENTITIES_ACCESS_URL = "/v0/entities";
    private static final int CORE_POOL_SIZE = 1;
    private static final int MAX_POOL_SIZE = 12;
    private static final int KEEP_ALIVE_TIME_MS = 50;
    private final ThreadPoolExecutor queryExecutor = new ThreadPoolExecutor(CORE_POOL_SIZE, MAX_POOL_SIZE,
            KEEP_ALIVE_TIME_MS, TimeUnit.MILLISECONDS, new LinkedBlockingStack<>());
    private final String selfUrl;
    private final int clusterSize;
    private static final String TIMESTAMP_HEADER_LITERAL = Sharder.TIMESTAMP_HEADER + ": ";

    public DatabaseHttpServer(ServiceConfig config, PersistentDao dao, Sharder sharder) throws IOException {
        super(transformConfig(config));
        this.dao = dao;
        this.selfUrl = config.selfUrl();
        this.sharder = sharder;
        this.clusterSize = config.clusterUrls().size();
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    public static void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            throw new NetworkException();
        }
    }

    private void handleEntityRequestTask(int method, String entityKey,
                                         int acknowledge, int from,
                                         boolean notProxy,
                                         byte[] body,
                                         HttpSession session) {
        if (entityKey.isEmpty()) {
            sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
            return;
        }
        MemorySegment key = fromString(entityKey);
        List<String> proxiedUrls = sharder.defineRequestProxyUrls(entityKey, from);
        CompletableFuture<Response> currentNodeResponse = null;
        if (proxiedUrls.contains(selfUrl)) {
            currentNodeResponse = CompletableFuture.supplyAsync(() -> switch (method) {
                case Request.METHOD_GET -> getEntity(key);
                case Request.METHOD_PUT -> putEntity(key, body);
                case Request.METHOD_DELETE -> deleteEntity(key);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            });
            if (notProxy) {
                try {
                    sendResponse(currentNodeResponse.get(), session);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY), session);
                }
            }
            proxiedUrls.remove(selfUrl);
        }
        List<CompletableFuture<Response>> responses = sharder.proxyRequest(method, entityKey, body, proxiedUrls);
        if (currentNodeResponse != null) responses.add(currentNodeResponse);
        // RequestManager appends response sending async operations inside the constructor
        new RequestsManager(responses, session, acknowledge, method);
    }

    @Path(ENTITY_ACCESS_URL)
    public void handleEntityRequest(Request request, HttpSession session,
                                    @Param(value = "id", required = true) String entityKey,
                                    @Param(value = "ack") Integer acknowledgeParam,
                                    @Param(value = "from") Integer fromParam,
                                    // For requests from other nodes
                                    @Param(value = "not-proxy") Boolean notProxyParam) {
        final int acknowledge;
        final int from;
        from = fromParam == null ? clusterSize : fromParam;
        acknowledge = acknowledgeParam == null ? from / 2 + 1 : acknowledgeParam;
        final boolean notProxy = notProxyParam != null && notProxyParam;
        if (acknowledge <= 0 || acknowledge > from || from > clusterSize) {
            sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
        }
        try {
            queryExecutor.execute(() -> handleEntityRequestTask(request.getMethod(),
                    entityKey, acknowledge, from, notProxy, request.getBody(), session));
        } catch (RejectedExecutionException e) {
            sendResponse(new Response(Response.SERVICE_UNAVAILABLE,
                    "Service temporary unavailable, retry later"
                            .getBytes(StandardCharsets.UTF_8)), session);
        }
    }

    private void handleEntitiesRequestTask(MemorySegment start, MemorySegment end, HttpSession session) {
        try {
            session.write(ChunkTransformUtility.HEADERS, 0, ChunkTransformUtility.HEADERS.length);
            for (Iterator<Entry<MemorySegment>> it = dao.get(start, end); it.hasNext(); ) {
                byte[] body = ChunkTransformUtility.makeContent(it.next());
                session.write(body, 0, body.length);
            }
            session.write(ChunkTransformUtility.EMPTY_CONTENT, 0, ChunkTransformUtility.EMPTY_CONTENT.length);
            session.close();
        } catch (IOException e) {
            throw new NetworkException();
        }
    }

    @Path(ENTITIES_ACCESS_URL)
    public void handleEntitiesRequest(Request request, HttpSession session,
                                      @Param(value = "start", required = true) String start,
                                      @Param(value = "end") String end) {
        if (start.isEmpty()) {
            sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY), session);
        }
        try {
            queryExecutor.execute(() -> handleEntitiesRequestTask(fromString(start),
                    fromString(end), session));
        } catch (RejectedExecutionException e) {
            sendResponse(new Response(Response.SERVICE_UNAVAILABLE,
                    "Service temporary unavailable, retry later"
                            .getBytes(StandardCharsets.UTF_8)), session);
        }

    }

    private Response putEntity(MemorySegment entityKey, byte[] entityValue) {
        dao.upsert(new BaseEntry<>(entityKey, MemorySegment.ofArray(entityValue), System.currentTimeMillis()));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response getEntity(MemorySegment entityKey) {
        Entry<MemorySegment> data = dao.get(entityKey);
        Response response = data == null || data.value() == null
                ? new Response(Response.NOT_FOUND, Response.EMPTY)
                : Response.ok(data.value().toArray(ValueLayout.OfByte.JAVA_BYTE));
        long timestamp = data == null ? Long.MAX_VALUE : data.timestamp();
        response.addHeader(TIMESTAMP_HEADER_LITERAL + timestamp);
        return response;
    }

    private Response deleteEntity(MemorySegment entityKey) {
        dao.upsert(new BaseEntry<>(entityKey, null, System.currentTimeMillis()));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static HttpServerConfig transformConfig(ServiceConfig serviceConfig) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.selectors = 5;
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    private static MemorySegment fromString(String data) {
        return (data == null) ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
