package ru.vk.itmo.test.smirnovandrew;

import one.nio.http.Header;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Logger;

public class MyServer extends HttpServer {
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
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            long exp = System.currentTimeMillis() + MyServerUtil.DURATION;
            executor.execute(() -> {
                try {
                    if (Objects.equals(request.getPath(), MyServerUtil.ROOT_ENTITIES)) {
                        entities(request, session);
                        return;
                    }

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
        return HttpRequest.newBuilder(URI.create(nodeUrl + MyServerUtil.ROOT + "?" + params))
                .method(request.getMethodName(), request.getBody() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .setHeader(MyServerUtil.X_SENDER_NODE, config.selfUrl())
                .build();
    }

    private CompletableFuture<Response> sendToAnotherNode(
            Request request,
            int ack,
            int from,
            String id,
            String clusterUrl,
            Function<MyServerDao, Response> operation
    ) {
        if (Objects.equals(clusterUrl, config.selfUrl())) {
            return CompletableFuture.completedFuture(operation.apply(dao));
        }

        var httpRequest = toHttpRequest(request, clusterUrl, String.format("id=%s&from=%d&ack=%d", id, from, ack));

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(MyServerUtil::processingResponse);
    }

    private Response handleLocalRequest(
            Request request,
            String id,
            Integer fromParam,
            Integer ackParam,
            String senderNode,
            Function<MyServerDao, Response> operation
    ) throws ExecutionException, InterruptedException, TimeoutException {
        final int from;
        if (Objects.isNull(fromParam)) {
            from = config.clusterUrls().size();
        } else {
            from = fromParam;
        }

        final int ack;
        if (Objects.isNull(ackParam)) {
            ack = quorum(from);
        } else {
            ack = ackParam;
        }

        String paramError = MyServerUtil.getParametersError(id, from, ack, config.clusterUrls().size());
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
            return sendToAnotherNode(request, ack, from, id, clusterUrl, operation)
                    .get(MyServerUtil.DURATION, TimeUnit.MILLISECONDS);
        }

        var completableResults = sortedNodes.stream()
                .map(nodeNumber -> sendToAnotherNode(
                        request,
                        ack,
                        from,
                        id,
                        config.clusterUrls().get(nodeNumber),
                        operation))
                .toList();

        return MyServerUtil.getResults(
                from,
                ack,
                completableResults,
                logger
        );
    }

    @Path(MyServerUtil.ROOT)
    @RequestMethod(Request.METHOD_GET)
    public Response get(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = MyServerUtil.X_SENDER_NODE) String senderNode,
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

    @Path(MyServerUtil.ROOT)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = MyServerUtil.X_SENDER_NODE) String senderNode,
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

    @Path(MyServerUtil.ROOT)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(
            @Param(value = "id", required = true) String id,
            @Param(value = "from") Integer from,
            @Param(value = "ack") Integer ack,
            @Header(value = MyServerUtil.X_SENDER_NODE) String senderNode,
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

    private void entities(
            Request request,
            HttpSession session
    ) {
        String start = request.getParameter("start=");
        String end = request.getParameter("end=");

        if (start.isEmpty()) {
            MyServerUtil.sendEmpty(session, logger, Response.BAD_REQUEST);
            return;
        }

        MyServerUtil.handleLocalEntitiesRequest(start, end, session, dao, logger);
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
