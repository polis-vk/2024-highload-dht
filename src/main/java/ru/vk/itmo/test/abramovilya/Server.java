package ru.vk.itmo.test.abramovilya;

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
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.abramovilya.dao.DaoFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

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
    private boolean alive = false;

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createConfig(config));
        this.config = config;
        this.dao = dao;

        for (String url : config.clusterUrls()) {
            if (!url.equals(config.selfUrl())) {
                httpClients.put(url, new HttpClient(new ConnectionString(url)));
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
    public Response getEntity(@Param(value = "id") String id) {
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
        return responseOk(entry.value());
    }

    private Optional<Response> getResponseFromAnotherNode(String id, ResponseProducer responseProducer) {
        int nodeNumber = mod(id.hashCode(), config.clusterUrls().size());
        String nodeUrl = config.clusterUrls().get(nodeNumber);
        if (nodeUrl.equals(config.selfUrl())) {
            return Optional.empty();
        }
        HttpClient client = httpClients.get(nodeUrl);
        try {
            return Optional.of(responseProducer.getResponse(client));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id") String id, Request request) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Optional<Response> responseO =
                getResponseFromAnotherNode(id, (client) -> client.put(urlSuffix(id), request.getBody()));
        if (responseO.isPresent()) {
            return responseO.get();
        }

        dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), MemorySegment.ofArray(request.getBody())));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Optional<Response> responseO =
                getResponseFromAnotherNode(id, (client) -> client.delete(urlSuffix(id)));
        if (responseO.isPresent()) {
            return responseO.get();
        }

        dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static String urlSuffix(String id) {
        return ENTITY_PATH + "?id=" + id;
    }

    private static Response responseOk(MemorySegment memorySegment) {
        return new Response(Response.OK, memorySegment.toArray(ValueLayout.JAVA_BYTE));
    }

    private static int mod(int i1, int i2) {
        int res = i1 % i2;
        return res >= 0 ? res : res + i2;
    }

    @FunctionalInterface
    public interface ResponseProducer {
        Response getResponse(HttpClient httpClient)
                throws InterruptedException, PoolException, IOException, HttpException;
    }
}
