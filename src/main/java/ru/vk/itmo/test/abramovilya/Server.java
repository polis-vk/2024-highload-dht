package ru.vk.itmo.test.abramovilya;

import one.nio.http.Header;
import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.abramovilya.util.Util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

public class Server extends HttpServer {
    public static final String ENTITY_PATH = "/v0/entity";
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    public static final int CORE_POOL_SIZE = 8;
    public static final int MAXIMUM_POOL_SIZE = 8;
    public static final int KEEP_ALIVE_TIME = 1;
    public static final int QUEUE_CAPACITY = 80;
    public static final String RESPONSE_NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private final Map<String, HttpClient> httpClients = new HashMap<>();
    private final ServiceConfig config;
    private final ExecutorService executorService = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAXIMUM_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY)
    );
    private final ServerDaoMiddleware serverDaoMiddleware;
    private boolean alive;

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createConfig(config));
        this.config = config;
        this.serverDaoMiddleware = new ServerDaoMiddleware(dao);

        for (String url : config.clusterUrls()) {
            if (!url.equals(config.selfUrl())) {
                HttpClient client = new HttpClient(new ConnectionString(url));
                client.setConnectTimeout(1000000);
                httpClients.put(url, client);
            }
        }
    }

    private static HttpServerConfig createConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        try {
            if (!isMethodAllowed(request.getMethod())) {
                session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
            }
            session.sendResponse(new Response(Response.BAD_REQUEST, "Unknown path".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            session.sendError(Response.INTERNAL_ERROR, "");
        }
    }

    private static boolean isMethodAllowed(int method) {
        return method == Request.METHOD_GET
                || method == Request.METHOD_PUT
                || method == Request.METHOD_DELETE;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (IOException e) {
                    logger.info("IOException for request: " + request);
                    throw new UncheckedIOException(e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.info("Execution rejected for request: " + request);
            session.sendError(Response.SERVICE_UNAVAILABLE, "");
        }
    }

    @Override
    public synchronized void start() {
        super.start();
        alive = true;
    }

    @Override
    public synchronized void stop() {
        if (alive) {
            super.stop();
            alive = false;
        }
        executorService.close();
        httpClients.values().forEach(HttpClient::close);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id") String id,
                              @Param(value = "from") Integer fromParam,
                              @Param(value = "ack") Integer ackParam,
                              @Header("X-SenderNode") String senderNode) {
        int from = fromParam == null ? config.clusterUrls().size() : fromParam;
        int ack = ackParam == null ? Util.quorum(from) : ackParam;
        Response badParams = verifyParams(id, from, ack);
        if (badParams != null) {
            return badParams;
        }
        if (senderNode == null) {
            return handleRequestFromUser(id, from, ack,
                    new Request(Request.METHOD_GET, urlSuffix(id), true),
                    response -> response.getStatus() < 300 || response.getStatus() == 404,
                    () -> serverDaoMiddleware.getEntryFromDao(id));
        }
        return serverDaoMiddleware.getEntryFromDao(id);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id") String id,
                              @Param(value = "from") Integer fromParam,
                              @Param(value = "ack") Integer ackParam,
                              @Header("X-SenderNode") String senderNode,
                              Request request) {
        int from = fromParam == null ? config.clusterUrls().size() : fromParam;
        int ack = ackParam == null ? Util.quorum(from) : ackParam;
        Response badParams = verifyParams(id, from, ack);
        if (badParams != null) {
            return badParams;
        }

        if (senderNode == null) {
            Request requestToAnotherNode = new Request(Request.METHOD_PUT, urlSuffix(id), true);
            requestToAnotherNode.addHeader("Content-Length: " + request.getBody().length);
            requestToAnotherNode.setBody(request.getBody());
            return handleRequestFromUser(id, from, ack,
                    requestToAnotherNode,
                    response -> response.getStatus() < 300,
                    () -> serverDaoMiddleware.putEntryIntoDao(id, request));
        }
        return serverDaoMiddleware.putEntryIntoDao(id, request);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id") String id,
                                 @Param(value = "from") Integer fromParam,
                                 @Param(value = "ack") Integer ackParam,
                                 @Header("X-SenderNode") String senderNode) {
        int from = fromParam == null ? config.clusterUrls().size() : fromParam;
        int ack = ackParam == null ? Util.quorum(from) : ackParam;
        Response badParams = verifyParams(id, from, ack);
        if (badParams != null) {
            return badParams;
        }

        if (senderNode == null) {
            return handleRequestFromUser(id, from, ack,
                    new Request(Request.METHOD_DELETE, urlSuffix(id), true),
                    response -> response.getStatus() < 300,
                    () -> serverDaoMiddleware.deleteValueFromDao(id));
        }
        return serverDaoMiddleware.deleteValueFromDao(id);
    }

    private Response verifyParams(String id, Integer from, Integer ack) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        if (ack <= 0 || from <= 0) {
            return new Response(Response.BAD_REQUEST,
                    "ack and from should be positive integers".getBytes(StandardCharsets.UTF_8));
        }
        if (ack > from) {
            return new Response(Response.BAD_REQUEST,
                    "ack can't be greater than from".getBytes(StandardCharsets.UTF_8));
        }
        if (from > config.clusterUrls().size()) {
            return new Response(Response.BAD_REQUEST,
                    "from can't be greater than total cluster size".getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    private Response handleRequestFromUser(String id, int from, int ack, Request request,
                                           Function<Response, Boolean> isResponseSuccess,
                                           Supplier<Response> daoOperation) {
        List<Integer> nodesRendezvousSorted = Util.getNodesRendezvousSorted(id, from, config);
        if (nodesRendezvousSorted.stream().map(config.clusterUrls()::get).noneMatch(config.selfUrl()::equals)) {
            return getResponseFromAnotherNode(Util.getNodeNumber(id, config), client -> client.invoke(request)).get();
        }
        List<Response> responses = new ArrayList<>();
        responses.add(daoOperation.get());
        for (int nodeNumber : nodesRendezvousSorted) {
            Optional<Response> responseO = getResponseFromAnotherNode(nodeNumber, client -> {
                request.addHeader("X-SenderNode: " + config.selfUrl());
                return client.invoke(request);
            });
            if (responseO.isPresent()) {
                Response response = responseO.get();
                if (isResponseSuccess.apply(response)) {
                    responses.add(responseO.get());
                }
            }
        }
        if (responses.size() < ack) {
            return new Response(RESPONSE_NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
        return responses.stream()
                .max(Comparator.comparingLong(Util::headerTimestampToLong))
                .get();
    }

    private Optional<Response> getResponseFromAnotherNode(int nodeNumber, ResponseProducer responseProducer) {
        return NodesCommunicationHandler.getResponseFromAnotherNode(nodeNumber, responseProducer, config, httpClients);
    }

    private static String urlSuffix(String id) {
        return ENTITY_PATH + "?id=" + id;
    }

    @FunctionalInterface

    public interface ResponseProducer {
        Response getResponse(HttpClient httpClient)
                throws InterruptedException, PoolException, IOException, HttpException;
    }
}
