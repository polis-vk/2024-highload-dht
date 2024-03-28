package ru.vk.itmo.test.smirnovandrew;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MyServer extends HttpServer {

    private static final String ROOT = "/v0/entity";

    private static final String ID = "id=";
    private static final String FROM = "from=";
    private static final String ACK = "ack=";
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private static final long DURATION = 1000L;

    private static final long RESPONSE_WAIT = 10;

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

    private static int quorum (int from) {
        return from / 2 + 1;
    }

    private Response sendToAnotherNode(Request request, HttpRequest.Builder clusterRequest, HttpSession session, String clusterUrl, String key) throws IOException {
        if (Objects.equals(clusterUrl, config.selfUrl())) {
            return switch (request.getMethod()) {
                case Request.METHOD_GET -> dao.getEntryFromDao(key);
                case Request.METHOD_DELETE -> dao.deleteValueFromDao(key);
                case Request.METHOD_PUT -> dao.putEntryIntoDao(key, request);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        }

        switch (request.getMethod()) {
            case Request.METHOD_GET -> clusterRequest.GET();
            case Request.METHOD_DELETE -> clusterRequest.DELETE();
            case Request.METHOD_PUT -> clusterRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(request.getBody()));
            default -> session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }

        var httpClient = httpClients.get(clusterUrl);

        try {
            var response = httpClient.sendAsync(
                    clusterRequest.build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            ).get(RESPONSE_WAIT, TimeUnit.SECONDS);

            return new Response(
                            responseFromStatusCode(response.statusCode()),
                            response.body()
                    );
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.info(e.getMessage());
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
//            Thread.currentThread().interrupt();
        }
    }

    private static long headerTimestampToLong(Response r) {
        String header = r.getHeader("X-TimeStamp: ");
        if (header == null) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(header);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String key = request.getParameter(ID);
        if (Objects.isNull(key) || key.isEmpty()) {
            logger.info(String.format("There is no id in query: %s", request.getQueryString()));
            sendEmpty(session, Response.BAD_REQUEST);
            return;
        }

        String clusterUrl = rendezvousClustersManager.getCluster(key);

        if (Objects.isNull(clusterUrl)) {
            logger.info(String.format("Cluster url is null, request = %s", request.getQueryString()));
            sendEmpty(session, Response.BAD_REQUEST);
            return;
        }

        int from;
        try {
            from = Integer.parseInt(request.getParameter(FROM));
        } catch (NumberFormatException e) {
            logger.info(e.getMessage());
            from = config.clusterUrls().size();
        }

        int ack;
        try {
            ack = Integer.parseInt(request.getParameter(ACK));
        } catch (NumberFormatException e) {
            logger.info(e.getMessage());
            ack = quorum(from);
        }

        String senderNode = request.getHeader("X-SenderNode");

        if (Objects.isNull(senderNode)) {
            handleLocalRequest(request, session);
            return;
        }

        var sortedNodes = RendezvousClusterManager.getSortedNodes(key, from, config);

        var clusterRequest = HttpRequest.newBuilder(
                URI.create(
                        String.join("",
                                clusterUrl,
                                ROOT,
                                String.join(
                                        "?",
                                        ID + key,
                                        FROM + from,
                                        ACK + ack
                                )
                        ))
        );
        if (sortedNodes.stream().map(config.clusterUrls()::get).noneMatch(config.selfUrl()::equals)) {
            var resp = sendToAnotherNode(request, clusterRequest, session, clusterUrl, key);
            session.sendResponse(resp);
            return;
        }
        List<Response> responses = new ArrayList<>();
        clusterRequest.header("X-SenderNode", config.selfUrl());
        for (int nodeNumber: sortedNodes) {
            var response = sendToAnotherNode(request, clusterRequest, session, config.clusterUrls().get(nodeNumber), key);
            if (response.getStatus() < 300) {
                responses.add(response);
            }
        }
        if (responses.size() < ack) {
            sendEmpty(session, NOT_ENOUGH_REPLICAS);
            return;
        }

        session.sendResponse(responses.stream()
                .max(Comparator.comparingLong(MyServer::headerTimestampToLong))
                .get());
    }

    private static String responseFromStatusCode(int statusCode) {
        return switch (statusCode) {
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 200 -> Response.OK;
            case 202 -> Response.ACCEPTED;
            case 201 -> Response.CREATED;
            case 503 -> Response.SERVICE_UNAVAILABLE;
            case 504 -> Response.GATEWAY_TIMEOUT;
            default -> Response.INTERNAL_ERROR;
        };
    }

    private void handleLocalRequest(Request request, HttpSession session) {
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

    private void sendEmpty(HttpSession session, String message) {
        try {
            session.sendResponse(new Response(message, Response.EMPTY));
        } catch (IOException e) {
            logger.info(e.getMessage());
        }
    }

    private static HttpServerConfig generateServerConfig(ServiceConfig config) {
        var serverConfig = new HttpServerConfig();
        var acceptorsConfig = new AcceptorConfig();

        acceptorsConfig.port = config.selfPort();
        acceptorsConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorsConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public MyServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        this(config, dao, Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors());
    }

//    private static HttpClient initializeClient(String url) {
//        return HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1000000))..build();
//    }

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
                .collect(Collectors.toMap(s -> s, _ -> HttpClient.newHttpClient(), (c, _) -> c));
    }

    private boolean isStringInvalid(String param) {
        return Objects.isNull(param) || "".equals(param);
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_GET)
    public Response get(
            @Param(value = "id", required = true) String id
    ) {
        if (isStringInvalid(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        return dao.getEntryFromDao(id);
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(
            @Param(value = "id", required = true) String id
    ) {
        if (isStringInvalid(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        return dao.deleteValueFromDao(id);
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(
            @Param(value = "id", required = true) String id,
            Request request
    ) {
        if (isStringInvalid(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        return dao.putEntryIntoDao(id, request);
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
