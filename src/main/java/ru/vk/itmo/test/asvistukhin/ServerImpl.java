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

import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerImpl extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);

    private static final String HTTP_SERVICE_NOT_AVAILABLE = "503";
    private static final int QUEUE_CAPACITY = 3000;

    private final ThreadPoolExecutor executor;
    private final AtomicBoolean isServerStopped = new AtomicBoolean(false);

    public ServerImpl(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
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
        Response response = List.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
        ).contains(request.getMethod())
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
            super.handleRequest(request, session);
        } catch (RejectedExecutionException executionException) {
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
                String response = ex instanceof HttpException ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
                session.sendError(response, null);
            } catch (IOException ioEx) {
                log.error("Failed send error response to client.", ioEx);
                session.close();
                Thread.currentThread().interrupt();
            }
        }
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
