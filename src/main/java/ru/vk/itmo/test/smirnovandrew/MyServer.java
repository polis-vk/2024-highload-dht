package ru.vk.itmo.test.smirnovandrew;

import one.nio.http.Header;
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
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Logger;

public class MyServer extends HttpServer {

    private static final String ROOT = "/v0/entity";
    private static final String X_SENDER_NODE = "X-SenderNode";
    private static final String X_TIMESTAMP = "X-TimeStamp";
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp:";
    public static final Map<Integer, String> HTTP_CODE = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR
    );
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final long DURATION = 1000L;

    private final MyServerDao dao;
    private final MyExecutor executor;
    private final Logger logger;
    private final HttpClient httpClient;
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
        this.httpClient = HttpClient.newHttpClient();
//        this.httpClients = config.clusterUrls().stream()
//                .filter(url -> !Objects.equals(url, config.selfUrl()))
//                .collect(Collectors.toMap(
//                        s -> s,
//                        MyServerUtil::createClient,
//                        (c, c1) -> c)
//                );
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

    private HttpRequest toHttpRequest(Request request, String nodeUrl, String params) {
        return HttpRequest.newBuilder(URI.create(nodeUrl + ROOT + "?id=" + params))
                .method(request.getMethodName(), request.getBody() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .setHeader(X_SENDER_NODE, config.selfUrl())
                .build();
    }

    private Response processingResponse(HttpResponse<byte[]> response) {
        String statusCode = HTTP_CODE.getOrDefault(response.statusCode(), null);
        if (statusCode == null) {
            return new Response(Response.INTERNAL_ERROR, response.body());
        } else {
            Response newResponse = new Response(statusCode, response.body());
            long timestamp = response.headers()
                    .firstValueAsLong(X_TIMESTAMP).orElse(0);
            newResponse.addHeader(X_TIMESTAMP + ": " + timestamp);
            return newResponse;
        }
    }

    private CompletableFuture<Response> sendToAnotherNode(
            Request request,
            String clusterUrl,
            Function<MyServerDao, Response> operation
    ) {
        if (Objects.equals(clusterUrl, config.selfUrl())) {
            return CompletableFuture.completedFuture(operation.apply(dao));
        }

        var httpRequest = toHttpRequest(request, clusterUrl, request.getParameter("id"));

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(this::processingResponse);

//        try {
//
//        } catch (InterruptedException e) {
//            logger.info(e.getMessage());
//            Thread.currentThread().interrupt();
//            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
//        } catch (HttpException | IOException | PoolException e1) {
//            logger.info(e1.getMessage());
//            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
//        }
    }

    private Response handleLocalRequest(
            Request request,
            String id,
            Integer fromParam,
            Integer ackParam,
            String senderNode,
            Function<MyServerDao, Response> operation
    ) throws ExecutionException, InterruptedException, TimeoutException {
        Integer from = fromParam;
        if (Objects.isNull(from)) {
            from = config.clusterUrls().size();
        }

        Integer ack;
        if (Objects.isNull(ackParam)) {
            ack = quorum(from);
        } else {
            ack = ackParam;
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
            return sendToAnotherNode(request, clusterUrl, operation).get(DURATION, TimeUnit.SECONDS);
        }

        List<Response> successResponses = new ArrayList<>();
        var successResponseCount = new AtomicInteger();
        var errorResponseCount = new AtomicInteger();
        var result = new CompletableFuture<Response>();

        var completableResults = sortedNodes.stream()
                .map(nodeNumber -> sendToAnotherNode(request, config.clusterUrls().get(nodeNumber), operation))
                .toList();

        for (var completableFuture : completableResults) {
            var responseFuture = completableFuture.whenComplete((r, throwable) -> {
                if (throwable == null || (r.getStatus() < 300 || (r.getStatus() == 404 && request.getMethod() == Request.METHOD_GET))) {
                    successResponseCount.incrementAndGet();
                    successResponses.add(r);
                } else {
                    errorResponseCount.incrementAndGet();
                }

                if (successResponseCount.get() == ack) {
                    result.complete(successResponses.stream()
                            .max(Comparator.comparingLong(MyServerUtil::headerTimestampToLong))
                            .get());
                }
            });
            if (responseFuture == null) {
                logger.info("Error completable future is null!");
            }
        }

        if (result.isDone()) {
            return result.get();
        }

        return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
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
    ) throws ExecutionException, InterruptedException, TimeoutException {
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
    ) throws ExecutionException, InterruptedException, TimeoutException {
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
    ) throws ExecutionException, InterruptedException, TimeoutException {
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
        httpClient.close();
    }
}