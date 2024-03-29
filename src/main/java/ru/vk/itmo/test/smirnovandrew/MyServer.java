package ru.vk.itmo.test.smirnovandrew;

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
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MyServer extends HttpServer {

    private static final String ROOT = "/v0/entity";
    private static final String X_SENDER_NODE = "X-SenderNode";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final long DURATION = 1000L;

    private static final int CONNECTION_TIMEOUT = 1000;

    private final MyServerDao dao;
    private final MyExecutor executor;
    private final Logger logger;
    private final Map<String, HttpClient> httpClients;
    private final RendezvousClusterManager rendezvousClustersManager;
    private final ServiceConfig config;

    private static final Set<Integer> METHOD_SET = new HashSet<>(List.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    ));

    private static HttpServerConfig generateServerConfig(ServiceConfig config) {
        var serverConfig = new HttpServerConfig();
        var acceptorsConfig = new AcceptorConfig();

        acceptorsConfig.port = config.selfPort();
        acceptorsConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorsConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public MyServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        this(config, dao, Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors());
    }

    public MyServer(
            ServiceConfig config,
            ReferenceDao dao,
            int corePoolSize,
            int availableProcessors
    ) throws IOException {
        super(generateServerConfig(config));
        this.rendezvousClustersManager = new RendezvousClusterManager(config);
        this.config = config;
        this.dao = new MyServerDao(dao);
        this.executor = new MyExecutor(corePoolSize, availableProcessors);
        this.logger = Logger.getLogger(MyServer.class.getName());
        this.httpClients = config.clusterUrls().stream()
                .filter(url -> !Objects.equals(url, config.selfUrl()))
                .collect(Collectors.toMap(
                        s -> s,
                        url -> {
                            var client = new HttpClient(new ConnectionString(url));
                            client.setConnectTimeout(CONNECTION_TIMEOUT);
                            return client;
                        },
                        (c, c1) -> c)
                );
    }

    private void sendEmpty(HttpSession session, String message) {
        try {
            session.sendResponse(new Response(message, Response.EMPTY));
        } catch (IOException e) {
            logger.info(e.getMessage());
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            long exp = System.currentTimeMillis() + DURATION;
            executor.execute(() -> {
                try {
                    if (System.currentTimeMillis() > exp) {
                        sendEmpty(session, Response.SERVICE_UNAVAILABLE);
                    } else {
                        super.handleRequest(request, session);
                    }
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    sendEmpty(session, Response.INTERNAL_ERROR);
                } catch (Exception e) {
                    logger.info(e.getMessage());
                    sendEmpty(session, Response.BAD_REQUEST);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.info(e.getMessage());
            sendEmpty(session, "429 Too Many Requests");
        }
    }

    private static int quorum(int from) {
        return from / 2 + 1;
    }

    private Response sendToAnotherNode(
            Request request,
            String clusterUrl,
            Function<MyServerDao, Response> operation
    ) {
        if (Objects.equals(clusterUrl, config.selfUrl())) {
            return operation.apply(dao);
        }

        var httpClient = httpClients.get(clusterUrl);

        try {
            return httpClient.invoke(request);
        } catch (InterruptedException e) {
            logger.info(e.getMessage());
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (HttpException | IOException | PoolException e) {
            logger.info(e.getMessage());
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private static long headerTimestampToLong(Response r) {
        String header = r.getHeader("X-TimeStamp: ");
        if (header == null) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(header);
    }

    private Response handleLocalRequest(
            Request request,
            String id,
            Integer from,
            Integer ack,
            String senderNode,
            Function<MyServerDao, Response> operation
    ) {
        if (Objects.isNull(from)) {
            from = config.clusterUrls().size();
        }

        if (Objects.isNull(ack)) {
            ack = quorum(from);
        }

        String paramError = getParametersError(id, from, ack);
        if (Objects.nonNull(paramError)) {
            return new Response(Response.BAD_REQUEST, paramError.getBytes(StandardCharsets.UTF_8));
        }

        if (Objects.nonNull(senderNode) && !senderNode.isEmpty()) {
            return operation.apply(dao);
        }

        String clusterUrl = rendezvousClustersManager.getCluster(id);

        if (Objects.isNull(clusterUrl)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        var sortedNodes = RendezvousClusterManager.getSortedNodes(id, from, config);

        if (sortedNodes.stream().map(config.clusterUrls()::get).noneMatch(config.selfUrl()::equals)) {
            return sendToAnotherNode(request, clusterUrl, operation);
        }

        request.addHeader(String.join(": ",X_SENDER_NODE, config.selfUrl()));
        var responses = sortedNodes.stream()
                .map(nodeNumber ->
                        sendToAnotherNode(request, config.clusterUrls().get(nodeNumber), operation)
                )
                .filter(response ->
                        response.getStatus() < 300 || (response.getStatus() == 404 && request.getMethod() == Request.METHOD_GET)
                )
                .toList();

        if (responses.size() < ack) {
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }

        return responses.stream()
                .max(Comparator.comparingLong(MyServer::headerTimestampToLong))
                .get();
    }

    private String getParametersError(String id, Integer from, Integer ack) {
        if (Objects.isNull(id) || id.isEmpty()) {
            return "Invalid id provided";
        }

        if (ack <= 0) {
            return "Too small ack";
        }

        if (from <= 0) {
            return "Too small from";
        }

        int clusterSize = config.clusterUrls().size();
        if (from > clusterSize) {
            return String.format("From is greater than cluster size: from=%d, clusterSize=%d", from, clusterSize);
        }

        if (ack > from) {
            return String.format("Ack is greater than from: ack=%d, from=%d", ack, from);
        }

        return null;
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_GET)
    public Response get(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = X_SENDER_NODE) String xSenderNode,
            Request request
    ) {
        return handleLocalRequest(
                request,
                id,
                from,
                ack,
                xSenderNode,
                dao -> dao.getEntryFromDao(id)
        );
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = X_SENDER_NODE) String xSenderNode,
            Request request
    ) {
        return handleLocalRequest(
                request,
                id,
                from,
                ack,
                xSenderNode,
                dao -> dao.deleteValueFromDao(id)
        );
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = X_SENDER_NODE) String xSenderNode,
            Request request
    ) {
        request.addHeader("Content-Length: " + request.getBody().length);
        request.setBody(request.getBody());

        return handleLocalRequest(
                request,
                id,
                from,
                ack,
                xSenderNode,
                dao -> dao.putEntryIntoDao(id, request)
        );
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(
                METHOD_SET.contains(request.getMethod())
                        ?
                        new Response(Response.BAD_REQUEST, Response.EMPTY) :
                        new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY)
        );
    }

    @Override
    public synchronized void stop() {
        this.executor.shutdown();
        super.stop();
        httpClients.values().forEach(HttpClient::close);
    }
}
