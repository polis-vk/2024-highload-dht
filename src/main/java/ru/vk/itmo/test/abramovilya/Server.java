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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

public class Server extends HttpServer {
    public static final String ENTITY_PATH = "/v0/entity";
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    public static final int CORE_POOL_SIZE = 8;
    public static final int MAXIMUM_POOL_SIZE = 8;
    public static final int KEEP_ALIVE_TIME = 1;
    public static final int QUEUE_CAPACITY = 80;
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
                client.setConnectTimeout(100);
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
                              @Header("X-Timestamp") Integer timestamp) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Optional<Response> responseO =
                getResponseFromAnotherNode(id, (client) -> client.get(urlSuffix(id)));
        if (responseO.isPresent()) {
            return responseO.get();
        }

        Entry<MemorySegment> entry = dao.get(DaoFactory.fromString(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        try {
            ValueWithTimestamp valueWithTimestamp =
                    (ValueWithTimestamp) byteArrayToObject(entry.value().toArray(ValueLayout.JAVA_BYTE));
            if (valueWithTimestamp.value() == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
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
                              Request request) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Optional<Response> responseO =
                getResponseFromAnotherNode(id, (client) -> client.put(urlSuffix(id), request.getBody()));
        if (responseO.isPresent()) {
            return responseO.get();
        }

        ValueWithTimestamp valueWithTimestamp = new ValueWithTimestamp(request.getBody(), System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), MemorySegment.ofArray(objToByteArray(valueWithTimestamp))));
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id") String id,
                                 @Param(value = "from") Integer from,
                                 @Param(value = "ack") Integer ack) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Optional<Response> responseO =
                getResponseFromAnotherNode(id, (client) -> client.delete(urlSuffix(id)));
        if (responseO.isPresent()) {
            return responseO.get();
        }

        ValueWithTimestamp valueWithTimestamp = new ValueWithTimestamp(null, System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), MemorySegment.ofArray(objToByteArray(valueWithTimestamp))));
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Optional<Response> getResponseFromAnotherNode(String id, ResponseProducer responseProducer) {
        int nodeNumber = getNodeNumber(id);
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

    private List<Integer> getNodesRendezvousSorted(String key) {
        int clusterSize = config.clusterUrls().size();
        List<NumberToHash> numberToHashList = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            numberToHashList.add(new NumberToHash(i, Hash.murmur3(key + i)));
        }
        return numberToHashList.stream()
                .sorted(Comparator.comparingInt(NumberToHash::hash))
                .map(NumberToHash::number)
                .toList();
    }

    private record NumberToHash(int number, int hash) {
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

    private static byte[] objToByteArray(Object object) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(object);
            return byteArrayOutputStream.toByteArray();
        }
    }

    private static Object byteArrayToObject(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            return objectInputStream.readObject();
        }
    }

    @FunctionalInterface

    public interface ResponseProducer {
        Response getResponse(HttpClient httpClient)
                throws InterruptedException, PoolException, IOException, HttpException;
    }
}
