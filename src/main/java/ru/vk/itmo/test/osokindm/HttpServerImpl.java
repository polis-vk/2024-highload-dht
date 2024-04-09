package ru.vk.itmo.test.osokindm;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HttpServerImpl extends HttpServer {

    private static final String ID_REQUEST = "id=";
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ThreadPoolExecutor requestWorkers;

    public HttpServerImpl(HttpServerConfig config) throws IOException {
        super(config);
        requestWorkers = new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().availableProcessors(),
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(600),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        requestWorkers.prestartAllCoreThreads();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            requestWorkers.shutdown();
            if (!requestWorkers.awaitTermination(KEEP_ALIVE_TIME, TimeUnit.SECONDS)) {
                requestWorkers.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOGGER.error("Error occurred while stopping request workers");
            requestWorkers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        String id = request.getParameter(ID_REQUEST);
        if (id == null || id.isEmpty()) {
            try {
                handleDefault(request, session);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            try {
                handleAsync(session, () -> {
                    try {
                        super.handleRequest(request, session);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    private void handleAsync(HttpSession session, Runnable runnable) throws IOException {
        try {
            requestWorkers.execute(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    LOGGER.error("Exception during handleRequest", e);
                    try {
                        session.sendError(Response.INTERNAL_ERROR, null);
                    } catch (IOException ex) {
                        LOGGER.error("Exception while sending close connection", e);
                        session.scheduleClose();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.warn("Workers pool queue overflow", e);
            session.sendError(Response.SERVICE_UNAVAILABLE, null);
        }
    }

}
