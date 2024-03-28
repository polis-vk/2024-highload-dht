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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ServerImpl extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);

    private static final List<Integer> ALLOWED_METHODS = List.of(
        Request.METHOD_GET,
        Request.METHOD_PUT,
        Request.METHOD_DELETE
    );
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp:";
    private static final String HTTP_SERVICE_NOT_AVAILABLE = "503";
    private static final String NOT_ENOUGH_REPLICAS_RESPONSE = "504 Not Enough Replicas";
    private static final int QUEUE_CAPACITY = 3000;

    private final ThreadPoolExecutor executor;
    private final ServiceConfig serviceConfig;
    private final RequestHandler requestHandler;
    private final ProxyRequestHandler proxyRequestHandler;
    private final AtomicBoolean isServerStopped = new AtomicBoolean(false);

    public ServerImpl(ServiceConfig serviceConfig, PersistentDao persistentDao, ProxyRequestHandler proxyRequestHandler) throws IOException {
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

    private void wrapHandleRequest(Request request, HttpSession session) {
        try {
            if (!ALLOWED_METHODS.contains(request.getMethod())) {
                handleDefault(request, session);
                return;
            }

            RequestWrapper parameters = new RequestWrapper(request, serviceConfig.clusterUrls().size());
            if (request.getHeader(ProxyRequestHandler.IS_SELF_PROCESS) == null) {
                processFirstRequest(request, session, parameters);
            } else {
                session.sendResponse(requestHandler.handle(request));
            }
        }
        catch (RejectedExecutionException executionException) {
            try {
                log.error("Rejected execution new request.", executionException);
                session.sendError(HTTP_SERVICE_NOT_AVAILABLE, "Server is overload.");
            } catch (IOException ex) {
                log.error("Failed send error response to client.", ex);
                session.close();
                Thread.currentThread().interrupt();
            }
        } catch (Exception ex) {
            try {
                String response = ex.getClass() == HttpException.class || ex.getClass() == IllegalArgumentException.class
                    ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
                session.sendError(response, null);
            } catch (IOException ioEx) {
                log.error("Failed send error response to client.", ioEx);
                session.close();
                Thread.currentThread().interrupt();
            }
        }
    }
    private void processFirstRequest(Request request, HttpSession session, RequestWrapper parameters) throws IOException {
        List<String> nodeUrls = proxyRequestHandler.getNodesByHash(parameters.from);
        if (nodeUrls.size() < parameters.from) {
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        }

        boolean isSelfProcessing = nodeUrls.remove(serviceConfig.selfUrl());
        Map<String, Response> responses = proxyRequestHandler.proxyRequests(request, nodeUrls);

        if (isSelfProcessing) {
            responses.put(serviceConfig.selfUrl(), requestHandler.handle(request));
        }

        List<Response> validResponses = responses.values().stream()
            .filter(response -> isSuccessProcessed(response.getStatus()))
            .collect(Collectors.toList());
        if (validResponses.size() >= parameters.ack) {
            if (request.getMethod() == Request.METHOD_GET) {
                validResponses.sort(
                    Comparator.comparingLong(r -> {
                            String timestamp = r.getHeader(RequestWrapper.NIO_TIMESTAMP_STRING_HEADER);
                            return timestamp == null ? 0 : Long.parseLong(timestamp);
                        }
                    )
                );
                session.sendResponse(validResponses.getLast());
            } else {
                session.sendResponse(validResponses.getFirst());
            }
        } else {
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        }

    }

    private boolean isSuccessProcessed(int status) {
        // not server and time limit errors
        return status < 500;
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
