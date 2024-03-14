package ru.vk.itmo.test.pavelemelyanov;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.pavelemelyanov.ExecutorServiceConfig.KEEP_ALIVE_TIME;

public class MyServer extends HttpServer {
    private static final String V0_PATH = "/v0/entity";
    private static final String ID_PARAM = "id";
    private static final Logger logger = LoggerFactory.getLogger(MyServer.class);
    private final ReferenceDao dao;
    private final ExecutorService workersPool;
    private final Map<String, HttpClient> httpClients = new HashMap<>();
    private final ConsistentHashing shards;
    private final String selfUrl;

    public MyServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createServerConfig(config));
        selfUrl = config.selfUrl();
        shards = new ConsistentHashing();
        this.dao = dao;
        workersPool = configureWorkersPool();

        for (String url : config.clusterUrls()) {
            int nodeCount = 1;
            HashSet<Integer> nodeSet = new HashSet<>(nodeCount);
            HashService hashService = new HashService();
            for (int i = 0; i < nodeCount; ++i) {
                nodeSet.add(hashService.digest(url.getBytes(StandardCharsets.UTF_8)));
            }
            shards.addShard(url, nodeSet);

            httpClients.put(url, new HttpClient(new ConnectionString(url)));
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        for (HttpClient httpClient : httpClients.values()) {
            if (httpClient != null && !httpClient.isClosed()) {
                httpClient.close();
            }
        }
        workersPool.close();
    }

    @Path(V0_PATH)
    @RequestMethod(Request.METHOD_GET)
    public final Response get(@Param(ID_PARAM) String id, Request request) {
        if (id == null || id.isBlank()) {
            return null;
        }

        MemorySegment key = convertFromString(id);

        if (canBeForward(id)) {
            return shardLookup(id, request);
        }

        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(V0_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public final Response put(@Param(ID_PARAM) String id, Request request) {
        if (id == null || id.isBlank()) {
            return null;
        }

        MemorySegment key = convertFromString(id);

        if (canBeForward(id)) {
            return shardLookup(id, request);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                key,
                MemorySegment.ofArray(request.getBody())
        );

        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(V0_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public final Response delete(@Param(ID_PARAM) String id, Request request) {
        if (id == null || id.isBlank()) {
            return null;
        }

        MemorySegment key = convertFromString(id);

        if (canBeForward(id)) {
            return shardLookup(id, request);
        }

        dao.upsert(new BaseEntry<>(key, null));

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(V0_PATH)
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
            workersPool.execute(() -> {
                if (System.nanoTime() > startTime + KEEP_ALIVE_TIME) {
                    sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY), session);
                    return;
                }

                try {
                    super.handleRequest(request, session);
                } catch (IOException e) {
                    logger.error("IOException in handleRequest workersPool");
                    sendResponse(
                            new Response(
                                    Response.INTERNAL_ERROR,
                                    Response.EMPTY
                            ),
                            session
                    );
                } catch (Exception e) {
                    sendResponse(
                            new Response(
                                    Response.BAD_REQUEST,
                                    Response.EMPTY
                            ),
                            session
                    );
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("RejectedExecutionException in handleRequest: " + request + session);
            sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY), session);
        }
    }

    private void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("IOException in sendResponse: " + response + session);
        }
    }

    private static ExecutorService configureWorkersPool() {
        return new ThreadPoolExecutor(
                ExecutorServiceConfig.CORE_POOL_SIZE,
                ExecutorServiceConfig.MAX_CORE_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.MILLISECONDS,
                ExecutorServiceConfig.queue,
                ExecutorServiceConfig.HANDLER
        );
    }

    private boolean canBeForward(final String id) {
        return !shards.getShardByKey(id).equals(selfUrl);
    }

    private Response shardLookup(final String id, final Request request) {
        Response response;
        Request redirect = new Request(request);
        try {
            response = httpClients.get(shards.getShardByKey(id)).invoke(redirect, 500);
        } catch (Exception e) {
            response = new Response(Response.BAD_GATEWAY, Response.EMPTY);
        }
        return new Response(response.getHeaders()[0], response.getBody());
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

    private static MemorySegment convertFromString(String value) {
        return MemorySegment.ofArray(value.getBytes(StandardCharsets.UTF_8));
    }
}
