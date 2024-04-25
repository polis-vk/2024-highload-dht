package ru.vk.itmo.test.asvistukhin;

import one.nio.async.CustomThreadFactory;
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
import ru.vk.itmo.test.asvistukhin.dao.PersistentDao;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerImpl extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);

    private static final String ENTITY_PATH = "/v0/entity";
    private static final String ENTITIES_PATH = "/v0/entities";
    private static final Integer INTERNAL_SERVER_ERROR_CODE = 500;
    private static final List<Integer> NOT_SUCCESSFUL_BAD_REQUESTS_CODES = List.of(429);
    private static final List<Integer> ALLOWED_METHODS = List.of(
        Request.METHOD_GET,
        Request.METHOD_PUT,
        Request.METHOD_DELETE
    );

    private static final String NOT_ENOUGH_REPLICAS_RESPONSE = "504 Not Enough Replicas";
    private static final int QUEUE_CAPACITY = 3000;

    private final ThreadPoolExecutor executor;
    private final ServiceConfig serviceConfig;
    private final RequestHandler requestHandler;
    private final ProxyRequestHandler proxyRequestHandler;
    private final AtomicBoolean isServerStopped = new AtomicBoolean(false);

    public ServerImpl(
        ServiceConfig serviceConfig,
        PersistentDao persistentDao,
        ProxyRequestHandler proxyRequestHandler
    ) throws IOException {
        super(createHttpServerConfig(serviceConfig));
        this.serviceConfig = serviceConfig;
        this.requestHandler = new RequestHandler(persistentDao);
        this.proxyRequestHandler = proxyRequestHandler;
        executor = new ThreadPoolExecutor(
            50,
            150,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            new CustomThreadFactory("server-executor", false),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        executor.execute(() -> wrapHandleRequest(request, session));
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = ALLOWED_METHODS.contains(request.getMethod())
            ? new Response(Response.BAD_REQUEST, Response.EMPTY)
            : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        if (isServerStopped.getAndSet(true)) {
            return;
        }

        executor.close();
        super.stop();
    }

    public static boolean isSuccessProcessed(int status) {
        return status < INTERNAL_SERVER_ERROR_CODE && !NOT_SUCCESSFUL_BAD_REQUESTS_CODES.contains(status);
    }

    private void wrapHandleRequest(Request request, HttpSession session) {
        try {
            if (!ALLOWED_METHODS.contains(request.getMethod())) {
                handleDefault(request, session);
                return;
            }

            switch (request.getPath()) {
                case ENTITY_PATH -> {
                    RequestWrapper parameters = new RequestWrapper(request, serviceConfig.clusterUrls().size());
                    if (request.getHeader(RequestWrapper.SELF_HEADER) == null) {
                        processFirstRequest(request, session, parameters);
                    } else {
                        session.sendResponse(requestHandler.handleEntity(request));
                    }
                }
                case ENTITIES_PATH -> requestHandler.handleEntities(request, session);
                default -> handleDefault(request, session);
            }
        } catch (Exception ex) {
            try {
                String response = ex.getClass() == HttpException.class
                    || ex.getClass() == IllegalArgumentException.class
                    ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
                session.sendError(response, null);
            } catch (IOException ioEx) {
                log.error("Failed send error response to client.", ioEx);
                session.close();
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void processFirstRequest(
            Request request,
            HttpSession session,
            RequestWrapper parameters
    ) throws IOException, ExecutionException, InterruptedException {
        List<String> nodeUrls = proxyRequestHandler.getNodesByHash(parameters.from);

        if (nodeUrls.size() < parameters.from) {
            sendNotEnoughReplicasResponse(session);
            return;
        }

        boolean isSelfProcessing = nodeUrls.remove(serviceConfig.selfUrl());

        List<CompletableFuture<Response>> futures = new CopyOnWriteArrayList<>();
        List<Response> validResponses = new CopyOnWriteArrayList<>();
        AtomicInteger unsuccessfulResponsesCount = new AtomicInteger(0);

        proxyRequestHandler.proxyRequests(
                request,
                nodeUrls,
                parameters.ack,
                futures,
                validResponses,
                unsuccessfulResponsesCount
        );

        if (isSelfProcessing) {
            requestHandler.handle(request, futures, validResponses, unsuccessfulResponsesCount);
        }

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.stream()
                        .limit(parameters.ack)
                        .toArray(CompletableFuture[]::new)
        );

        try {
            allFutures.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Timeout reached while waiting for responses");
        }

        if (validResponses.size() >= parameters.ack) {
            if (request.getMethod() == Request.METHOD_GET) {
                sendResponseToClient(session, validResponses);
            } else {
                session.sendResponse(validResponses.getFirst());
            }
        } else {
            sendNotEnoughReplicasResponse(session);
        }
    }

    private void sendResponseToClient(HttpSession session, List<Response> validResponses) throws IOException {
        Response lastResponse = null;
        long maxTimestamp = Long.MIN_VALUE;

        for (Response response : validResponses) {
            String timestamp = response.getHeader(RequestWrapper.NIO_TIMESTAMP_STRING_HEADER);
            if (timestamp != null) {
                long currentTimestamp = Long.parseLong(timestamp);
                if (currentTimestamp > maxTimestamp) {
                    maxTimestamp = currentTimestamp;
                    lastResponse = response;
                }
            }
        }

        if (lastResponse == null) {
            sendNotEnoughReplicasResponse(session);
        } else {
            session.sendResponse(lastResponse);
        }
    }

    private void sendNotEnoughReplicasResponse(HttpSession session) throws IOException {
        session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }
}
