package ru.vk.itmo.test.dariasupriadkina;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ru.vk.itmo.test.dariasupriadkina.HeaderConstraints.FROM_HEADER;
import static ru.vk.itmo.test.dariasupriadkina.HeaderConstraints.TIMESTAMP_MILLIS_HEADER;
import static ru.vk.itmo.test.dariasupriadkina.HeaderConstraints.TIMESTAMP_MILLIS_HEADER_NORMAL;

public class Server extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(Server.class.getName());
    private final ExecutorService workerExecutor;
    private final Set<Integer> permittedMethods =
            Set.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE);
    private final String selfUrl;
    private final ShardingPolicy shardingPolicy;
    private final HttpClient httpClient;
    private static final int REQUEST_TIMEOUT_SEC = 2;
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

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
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
                    if (!permittedMethods.contains(request.getMethod())) {
                        handleDefault(request, session);
                    }
                    if (request.getHeader(FROM_HEADER) == null) {
                        request.addHeader(FROM_HEADER + selfUrl);

                        Map<String, Integer> ackFrom = getFromAndAck(request);
                        int from = ackFrom.get("from");
                        int ack = ackFrom.get("ack");

                        Response response = mergeResponses(broadcast(
                                        shardingPolicy.getNodesById(utils.getIdParameter(request), from), request, ack),
                                ack, from);

                        session.sendResponse(response);

                    } else {
                        Response resp = selfHandler.handleRequest(request);
                        checkTimestampHeaderExistenceAndSet(resp);
                        session.sendResponse(resp);
                    }

                } catch (Exception e) {
                    logger.error("Unexpected error", e);
                    try {
                        if (e.getClass() == HttpException.class) {
                            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                        }
                    } catch (IOException ex) {
                        logger.error("Failed to send error response", e);
                        session.close();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("Service is unavailable", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));

        }
    }

    private List<Response> broadcast(List<String> nodes, Request request, int ack) {
        int internalAck = ack;
        List<Response> responses = new ArrayList<>(ack);
        Response response;
        if (nodes.contains(selfUrl)) {
            response = selfHandler.handleRequest(request);
            checkTimestampHeaderExistenceAndSet(response);
            responses.add(response);
            nodes.remove(selfUrl);
            if (--internalAck == 0) {
                return responses;
            }
        }

        for (String node : nodes) {
            response = handleProxy(utils.getEntryUrl(node, utils.getIdParameter(request)), request);
            if (response.getStatus() < 500) {
                checkTimestampHeaderExistenceAndSet(response);
                responses.add(response);
                if (--internalAck == 0) {
                    return responses;
                }
            }
        }
        return responses;
    }

    private void checkTimestampHeaderExistenceAndSet(Response response) {
        if (response.getHeader(TIMESTAMP_MILLIS_HEADER) == null) {
            response.addHeader(
                    TIMESTAMP_MILLIS_HEADER + "0"
            );
        }
    }

    private Response mergeResponses(List<Response> responses, int ack, int from) {
        if (responses.stream().filter(response -> response.getStatus() == 400).count() == from
                || ack > from
                || from > clusterUrls.size()
                || ack <= 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        if (responses.stream().filter(response -> response.getStatus() == 200
                || response.getStatus() == 404
                || response.getStatus() == 202
                || response.getStatus() == 201).count() < ack) {
            return new Response("504 Not Enough Replicas", Response.EMPTY);
        }
        return responses.stream().max((o1, o2) -> {
            Long header1 = Long.parseLong(o1.getHeader(TIMESTAMP_MILLIS_HEADER));
            Long header2 = Long.parseLong(o2.getHeader(TIMESTAMP_MILLIS_HEADER));
            return header1.compareTo(header2);
        }).get();
    }

    public Response handleProxy(String redirectedUrl, Request request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(redirectedUrl))
                    .header(FROM_HEADER, selfUrl)
                    .method(request.getMethodName(), HttpRequest.BodyPublishers.ofByteArray(
                            request.getBody() == null ? new byte[]{} : request.getBody())
                    ).build();
            HttpResponse<byte[]> response = httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                    .get(REQUEST_TIMEOUT_SEC, TimeUnit.SECONDS);
            Response response1 = new Response(String.valueOf(response.statusCode()), response.body());
            if (response.headers().map().get(TIMESTAMP_MILLIS_HEADER_NORMAL) == null) {
                response1.addHeader(TIMESTAMP_MILLIS_HEADER + "0");
            } else {
                response1.addHeader(TIMESTAMP_MILLIS_HEADER
                        + response.headers().map().get(
                                TIMESTAMP_MILLIS_HEADER_NORMAL.toLowerCase(Locale.ROOT)).getFirst()
                );
            }

            return response1;
        } catch (ExecutionException e) {
            logger.error("Unexpected error", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (TimeoutException e) {
            logger.error("Timeout reached", e);
            return new Response(Response.REQUEST_TIMEOUT, Response.EMPTY);
        } catch (InterruptedException e) {
            logger.error("Service is unavailable", e);
            Thread.currentThread().interrupt();
            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
        }
    }

    private Map<String, Integer> getFromAndAck(Request request) {
        int from = Integer.parseInt(request.getParameter("from=", String.valueOf(clusterUrls.size())));
        int ack = Integer.parseInt(request.getParameter("ack=", String.valueOf(clusterUrls.size() / 2 + 1)));

        return Map.of("from", from, "ack", ack);
    }
}
