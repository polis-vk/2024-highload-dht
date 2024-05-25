package ru.vk.itmo.test.dariasupriadkina;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedEntry;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.vk.itmo.test.dariasupriadkina.HeaderConstraints.FROM_HEADER;
import static ru.vk.itmo.test.dariasupriadkina.HeaderConstraints.FROM_HEADER_NORMAL;
import static ru.vk.itmo.test.dariasupriadkina.HeaderConstraints.TIMESTAMP_MILLIS_HEADER;
import static ru.vk.itmo.test.dariasupriadkina.HeaderConstraints.TIMESTAMP_MILLIS_HEADER_NORMAL;

public class Server extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(Server.class.getName());
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[]{};
    private final ExecutorService workerExecutor;
    private final Set<Integer> permittedMethods =
            Set.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE);
    private final String selfUrl;
    private final ShardingPolicy shardingPolicy;
    private final HttpClient httpClient;
    private final List<String> clusterUrls;
    private final Utils utils;
    private final SelfRequestHandler selfHandler;

    public Server(ServiceConfig config, Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao,
                  ThreadPoolExecutor workerExecutor, ThreadPoolExecutor nodeExecutor, ShardingPolicy shardingPolicy)
            throws IOException {
        super(createHttpServerConfig(config));
        this.workerExecutor = workerExecutor;
        this.shardingPolicy = shardingPolicy;
        this.selfUrl = config.selfUrl();
        this.clusterUrls = config.clusterUrls();
        this.httpClient = HttpClient.newBuilder().executor(nodeExecutor).build();
        this.utils = new Utils(dao);
        this.selfHandler = new SelfRequestHandler(dao, utils);
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (permittedMethods.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            workerExecutor.execute(() -> {
                try {
                    if (request.getURI().startsWith(Utils.LOCAL_STREAM_ENTRY_PREFIX)) {
                        selfHandler.handleRange(request, session);
                        return;
                    }
                    Map<String, Integer> ackFrom = getFromAndAck(request);
                    int from = ackFrom.get("from");
                    int ack = ackFrom.get("ack");
                    if (!permittedMethods.contains(request.getMethod())
                            || checkBadRequest(ack, from, request.getMethod())) {
                        handleDefault(request, session);
                        return;
                    }
                    if (request.getHeader(FROM_HEADER_NORMAL) == null) {
                        request.addHeader(FROM_HEADER + selfUrl);
                        collectResponsesCallback(
                                broadcast(
                                        shardingPolicy.getNodesById(utils.getIdParameter(request), from),
                                        request
                                ), ack, from, session);
                    } else {
                        Response resp = selfHandler.handleRequest(request);
                        checkTimestampHeaderExistenceAndSet(resp);
                        session.sendResponse(resp);
                    }
                } catch (Exception e) {
                    solveUnexpectedError(e, session);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("Service is unavailable", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new CustomHttpSession(socket, this);
    }

    private void solveUnexpectedError(Exception e, HttpSession session) {
        logger.error("Unexpected error", e);
        try {
            if (e.getClass() == HttpException.class) {
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            } else {
                session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            }
        } catch (Exception exception) {
            logger.error("Failed to send error response", exception);
            session.scheduleClose();
        }
    }

    private boolean checkBadRequest(int ack, int from, int method) {
        return !permittedMethods.contains(method) || ack > from || ack <= 0 || from > clusterUrls.size();
    }

    private List<CompletableFuture<Response>> broadcast(List<String> nodes, Request request) {
        List<CompletableFuture<Response>> futureResponses = new ArrayList<>(nodes.size());
        CompletableFuture<Response> response;
        if (nodes.contains(selfUrl)) {
            response = selfHandler.handleAsyncRequest(request);
            futureResponses.add(response);
            nodes.remove(selfUrl);
        }

        for (String node : nodes) {
            response = handleProxy(utils.getEntryUrl(node, utils.getIdParameter(request)), request);
            futureResponses.add(response);
        }
        return futureResponses;
    }

    private void checkTimestampHeaderExistenceAndSet(Response response) {
        if (response.getHeader(TIMESTAMP_MILLIS_HEADER) == null) {
            response.addHeader(
                    TIMESTAMP_MILLIS_HEADER + "0"
            );
        }
    }

    private void collectResponsesCallback(List<CompletableFuture<Response>> futureResponses,
                                          int ack, int from, HttpSession session) {
        List<Response> responses = new CopyOnWriteArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        for (CompletableFuture<Response> futureResponse : futureResponses) {
            futureResponse.whenCompleteAsync((response, exception) -> {

                if (exception == null && response.getStatus() < 500) {
                    checkTimestampHeaderExistenceAndSet(response);
                    responses.add(response);
                    successCount.incrementAndGet();
                    if (successCount.get() == ack) {
                        sendAsyncResponse(chooseResponse(responses), session);
                    }
                } else {
                    exceptionCount.incrementAndGet();
                    if (exceptionCount.get() > from - ack) {
                        sendAsyncResponse(new Response("504 Not enough replicas", Response.EMPTY), session);
                    }
                }

            }, workerExecutor).exceptionally(exception -> {
                logger.error("Error happened while collecting responses from nodes", exception);
                return null;
            });
        }
    }

    private void sendAsyncResponse(Response resp, HttpSession session) {
        try {
            session.sendResponse(resp);
        } catch (IOException e) {
            logger.error("Failed to send error response", e);
            session.scheduleClose();
        }
    }

    private Response chooseResponse(List<Response> responses) {
        return responses.stream().max((o1, o2) -> {
            Long header1 = Long.parseLong(o1.getHeader(TIMESTAMP_MILLIS_HEADER));
            Long header2 = Long.parseLong(o2.getHeader(TIMESTAMP_MILLIS_HEADER));
            return header1.compareTo(header2);
        }).get();
    }

    public CompletableFuture<Response> handleProxy(String redirectedUrl, Request request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(redirectedUrl))
                .header(FROM_HEADER_NORMAL, selfUrl)
                .method(request.getMethodName(), HttpRequest.BodyPublishers.ofByteArray(
                        request.getBody() == null ? EMPTY_BYTE_ARRAY : request.getBody())
                ).build();
        return httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(httpResponse -> {
                    Response response1 = new Response(String.valueOf(httpResponse.statusCode()), httpResponse.body());
                    if (httpResponse.headers().map().get(TIMESTAMP_MILLIS_HEADER_NORMAL) == null) {
                        response1.addHeader(TIMESTAMP_MILLIS_HEADER + "0");
                    } else {
                        response1.addHeader(TIMESTAMP_MILLIS_HEADER
                                + httpResponse.headers().map().get(
                                TIMESTAMP_MILLIS_HEADER_NORMAL.toLowerCase(Locale.ROOT)).getFirst()
                        );
                    }
                    return response1;
                }, workerExecutor);
    }

    private Map<String, Integer> getFromAndAck(Request request) {
        int from = Integer.parseInt(request.getParameter("from=", String.valueOf(clusterUrls.size())));
        int ack = Integer.parseInt(request.getParameter("ack=", String.valueOf(clusterUrls.size() / 2 + 1)));

        return Map.of("from", from, "ack", ack);
    }
}
