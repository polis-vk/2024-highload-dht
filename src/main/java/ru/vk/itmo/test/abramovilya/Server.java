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
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.abramovilya.dao.DaoFactory;
import ru.vk.itmo.test.abramovilya.util.Util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAXIMUM_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY)
    );
    private boolean alive;

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createConfig(config));
        this.config = config;
        this.dao = dao;

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
                              @Param(value = "from") Integer from,
                              @Param(value = "ack") Integer ack,
                              @Header("X-SenderNode") String senderNode) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        if (from == null) {
            from = config.clusterUrls().size();
        }
        if (ack == null) {
            ack = quorum(from);
        }
        if (ack <= 0 || from <= 0) {
            return new Response(Response.BAD_REQUEST, "ack and from should be positive integers".getBytes(StandardCharsets.UTF_8));
        }
        if (ack > from) {
            return new Response(Response.BAD_REQUEST, "ack can't be greater than from".getBytes(StandardCharsets.UTF_8));
        }
        if (from > config.clusterUrls().size()) {
            return new Response(Response.BAD_REQUEST, "from can't be greater than total cluster size".getBytes(StandardCharsets.UTF_8));
        }
        if (senderNode == null) {
            return handleRequestFromUser(id, from, ack,
                    client -> client.get(urlSuffix(id)),
                    client -> client.get(urlSuffix(id), "X-SenderNode: " + config.selfUrl()),
                    response -> response.getStatus() < 300 || response.getStatus() == 404,
                    () -> getEntryFromDao(id));
        }
        return getEntryFromDao(id);
    }

    private static int quorum(Integer from) {
        return from / 2 + 1;
    }

    private Response getEntryFromDao(String id) {
        Entry<MemorySegment> entry = dao.get(DaoFactory.fromString(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        try {
            ValueWithTimestamp valueWithTimestamp = Util.byteArrayToObject(entry.value().toArray(ValueLayout.JAVA_BYTE));
            if (valueWithTimestamp.value() == null) {
                Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                response.addHeader("X-Timestamp: " + valueWithTimestamp.timestamp());
                return response;
            }
            Response response = new Response(Response.OK, valueWithTimestamp.value());
            response.addHeader("X-Timestamp: " + valueWithTimestamp.timestamp());
            return response;
        } catch (IOException | ClassNotFoundException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id") String id,
                              @Param(value = "from") Integer from,
                              @Param(value = "ack") Integer ack,
                              @Header("X-SenderNode") String senderNode,
                              Request request) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        if (from == null) {
            from = config.clusterUrls().size();
        }
        if (ack == null) {
            ack = quorum(from);
        }
        if (ack <= 0 || from <= 0) {
            return new Response(Response.BAD_REQUEST, "ack and from should be positive integers".getBytes(StandardCharsets.UTF_8));
        }
        if (ack > from) {
            return new Response(Response.BAD_REQUEST, "ack can't be greater than from".getBytes(StandardCharsets.UTF_8));
        }
        if (from > config.clusterUrls().size()) {
            return new Response(Response.BAD_REQUEST, "from can't be greater than total cluster size".getBytes(StandardCharsets.UTF_8));
        }

        if (senderNode == null) {
            return handleRequestFromUser(id, from, ack,
                    client -> client.put(urlSuffix(id), request.getBody()),
                    client -> client.put(urlSuffix(id), request.getBody(), "X-SenderNode: " + config.selfUrl()),
                    response -> response.getStatus() < 300,
                    () -> putEntryIntoDao(id, request));
        }
        return putEntryIntoDao(id, request);
    }

    private Response putEntryIntoDao(String id, Request request) {
        ValueWithTimestamp valueWithTimestamp = new ValueWithTimestamp(request.getBody(), System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), MemorySegment.ofArray(Util.objToByteArray(valueWithTimestamp))));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }


    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id") String id,
                                 @Param(value = "from") Integer from,
                                 @Param(value = "ack") Integer ack,
                                 @Header("X-SenderNode") String senderNode) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        if (from == null) {
            from = config.clusterUrls().size();
        }
        if (ack == null) {
            ack = quorum(from);
        }
        if (ack <= 0 || from <= 0) {
            return new Response(Response.BAD_REQUEST, "ack and from should be positive integers".getBytes(StandardCharsets.UTF_8));
        }
        if (ack > from) {
            return new Response(Response.BAD_REQUEST, "ack can't be greater than from".getBytes(StandardCharsets.UTF_8));
        }
        if (from > config.clusterUrls().size()) {
            return new Response(Response.BAD_REQUEST, "from can't be greater than total cluster size".getBytes(StandardCharsets.UTF_8));
        }

        if (senderNode == null) {
            return handleRequestFromUser(id, from, ack,
                    client -> client.delete(urlSuffix(id)),
                    client -> client.delete(urlSuffix(id), "X-SenderNode: " + config.selfUrl()),
                    response -> response.getStatus() < 300,
                    () -> deleteValueFromDao(id));
        }
        return deleteValueFromDao(id);
    }

    private Response deleteValueFromDao(String id) {
        ValueWithTimestamp valueWithTimestamp = new ValueWithTimestamp(null, System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), MemorySegment.ofArray(Util.objToByteArray(valueWithTimestamp))));
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Response handleRequestFromUser(String id,
                                           int from,
                                           int ack,
                                           ResponseProducer delegateToAnotherNode,
                                           ResponseProducer askAnotherNode,
                                           Function<Response, Boolean> isResponseSuccess,
                                           Supplier<Response> daoOperation) {
        List<Integer> nodesRendezvousSorted = getNodesRendezvousSorted(id, from);
        if (nodesRendezvousSorted.stream().map(config.clusterUrls()::get).noneMatch(config.selfUrl()::equals)) {
            return getResponseFromAnotherNode(getNodeNumber(id), delegateToAnotherNode).get();
        }
        List<Response> responses = new ArrayList<>();
        responses.add(daoOperation.get());
        for (int nodeNumber : nodesRendezvousSorted) {
            Optional<Response> responseO = getResponseFromAnotherNode(nodeNumber, askAnotherNode);
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
                .max(Comparator.comparingLong(Server::headerTimestampToLong))
                .get();
    }

    private static long headerTimestampToLong(Response r) {
        String header = r.getHeader("X-TimeStamp: ");
        if (header == null) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(header);
    }

    private Optional<Response> getResponseFromAnotherNode(int nodeNumber, ResponseProducer responseProducer) {
        String nodeUrl = config.clusterUrls().get(nodeNumber);
        if (nodeUrl.equals(config.selfUrl())) {
            return Optional.empty();
        }
        HttpClient client = httpClients.get(nodeUrl);
        try {
            return Optional.of(responseProducer.getResponse(client));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (Exception e) {
            return Optional.of(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    private static String urlSuffix(String id) {
        return ENTITY_PATH + "?id=" + id;
    }

    private List<Integer> getNodesRendezvousSorted(String key, int amount) {
        int clusterSize = config.clusterUrls().size();
        List<Entry<Integer>> numberToHashList = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            numberToHashList.add(new BaseEntry<>(i, Hash.murmur3(key + i)));
        }
        return numberToHashList.stream()
                .sorted(Comparator.comparing(Entry::value, Comparator.reverseOrder()))
                .map(Entry::key)
                .limit(amount)
                .toList();
    }

    private int getNodeNumber(String key) {
        int clusterSize = config.clusterUrls().size();
        int maxHash = Integer.MIN_VALUE;
        int argMax = 0;
        for (int i = 0; i < clusterSize; i++) {
            int hash = Hash.murmur3(key + i);
            if (hash > maxHash) {
                maxHash = hash;
                argMax = i;
            }
        }
        return argMax;
    }

    @FunctionalInterface

    public interface ResponseProducer {
        Response getResponse(HttpClient httpClient)
                throws InterruptedException, PoolException, IOException, HttpException;
    }
}
