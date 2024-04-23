package ru.vk.itmo.test.smirnovandrew;

import one.nio.http.Header;
import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private static final int OK_STATUS = 300;
    private static final int NOT_FOUND_STATUS = 404;
    private static final String HEADER_DELIMITER = ": ";
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

    public MyServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        this(config, dao, Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors());
    }

    public MyServer(
            ServiceConfig config,
            ReferenceDao dao,
            int corePoolSize,
            int availableProcessors
    ) throws IOException {
        super(MyServerUtil.generateServerConfig(config));
        this.rendezvousClustersManager = new RendezvousClusterManager(config);
        this.config = config;
        this.dao = new MyServerDao(dao);
        this.executor = new MyExecutor(corePoolSize, availableProcessors);
        this.logger = Logger.getLogger(MyServer.class.getName());
        this.httpClients = config.clusterUrls().stream()
                .filter(url -> !Objects.equals(url, config.selfUrl()))
                .collect(Collectors.toMap(
                        s -> s,
                        MyServerUtil::createClient,
                        (c, c1) -> c)
                );
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            long exp = System.currentTimeMillis() + DURATION;
            executor.execute(() -> {
                try {
                    if (System.currentTimeMillis() > exp) {
                        MyServerUtil.sendEmpty(session, logger, Response.SERVICE_UNAVAILABLE);
                    } else {
                        super.handleRequest(request, session);
                    }
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    MyServerUtil.sendEmpty(session, logger, Response.INTERNAL_ERROR);
                } catch (Exception e) {
                    logger.info(e.getMessage());
                    MyServerUtil.sendEmpty(session, logger, Response.BAD_REQUEST);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.info(e.getMessage());
            MyServerUtil.sendEmpty(session, logger, "429 Too Many Requests");
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
        } catch (HttpException | IOException | PoolException e1) {
            logger.info(e1.getMessage());
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response handleLocalRequest(
            Request request,
            String id,
            Integer fromParam,
            Integer ackParam,
            String senderNode,
            Function<MyServerDao, Response> operation
    ) {
        Integer from = fromParam;
        if (Objects.isNull(from)) {
            from = config.clusterUrls().size();
        }

        Integer ack = ackParam;
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

        var sortedNodes = RendezvousClusterManager.getSortedNodes(from, config);

        if (sortedNodes.stream().map(config.clusterUrls()::get).noneMatch(config.selfUrl()::equals)) {
            return sendToAnotherNode(request, clusterUrl, operation);
        }

        request.addHeader(String.join(HEADER_DELIMITER, X_SENDER_NODE, config.selfUrl()));
        var responses = new ArrayList<Response>();
        for (int nodeNumber : sortedNodes) {
            var r = sendToAnotherNode(request, config.clusterUrls().get(nodeNumber), operation);
            if (r.getStatus() < OK_STATUS
                    || (r.getStatus() == NOT_FOUND_STATUS && request.getMethod() == Request.METHOD_GET)) {
                responses.add(r);
            }
        }

        if (responses.size() < ack) {
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }

        return MyServerUtil.getMaxTimestampResponse(responses);
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
            @Header(value = X_SENDER_NODE) String senderNode,
            Request request
    ) {
        return handleLocalRequest(
                request,
                id,
                from,
                ack,
                senderNode,
                d -> d.getEntryFromDao(id)
        );
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = X_SENDER_NODE) String senderNode,
            Request request
    ) {
        return handleLocalRequest(
                request,
                id,
                from,
                ack,
                senderNode,
                d -> d.deleteValueFromDao(id)
        );
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = X_SENDER_NODE) String senderNode,
            Request request
    ) {
        request.addHeader("Content-Length: " + request.getBody().length);
        request.setBody(request.getBody());

        return handleLocalRequest(
                request,
                id,
                from,
                ack,
                senderNode,
                d -> d.putEntryIntoDao(id, request)
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
