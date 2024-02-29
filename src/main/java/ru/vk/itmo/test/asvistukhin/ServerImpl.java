package ru.vk.itmo.test.asvistukhin;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerImpl extends HttpServer {
    private final Logger logger = Logger.getLogger(this.getClass().getName());

    private static final String HTTP_SERVICE_NOT_AVAILABLE = "503";
    private static final String EMPTY_RESPONSE = "";
    private static final int QUEUE_CAPACITY = 2500;

    private final ThreadPoolExecutor executor;

    public ServerImpl(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        executor = new ThreadPoolExecutor(
            50,
            150,
            15,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY, true),
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
        executor.close();
        super.stop();
    }

    private void wrapHandleRequest(Request request, HttpSession session) {
        try {
            super.handleRequest(request, session);
        } catch (RejectedExecutionException executionException) {
            try {
                logger.log(Level.SEVERE, "Rejected execution new request.", executionException);
                session.sendError(HTTP_SERVICE_NOT_AVAILABLE, "Server is overload.");
            } catch (IOException ioException) {
                logger.log(Level.SEVERE, "Failed send error response to client.", ioException);
                session.close();
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            try {
                String response = e.getClass() == HttpException.class ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
                session.sendError(response, EMPTY_RESPONSE);
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Failed send error response to client.", e);
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
