package ru.vk.itmo.test.osokindm;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HttpServerImpl extends HttpServer {

    private static final String DEFAULT_PATH = "/v0/entity";
    private static final String ID_REQUEST = "id=";
    private static final long KEEP_ALIVE_TIME = 60L;
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ExecutorService requestWorkers;
    private final DaoWrapper daoWrapper;

    public HttpServerImpl(ServiceConfig config, DaoWrapper daoWrapper) throws IOException {
        super(createServerConfig(config));
        this.daoWrapper = daoWrapper;
        // maximumPoolSize > availableProcessors, which may lead to increased context switching
        // corePoolSize and maximumPoolSize will be configured later with using wrk and profiler
        requestWorkers = new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().availableProcessors(),
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(600),
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Override
    public synchronized void stop() {
        try {
            daoWrapper.stop();
            requestWorkers.shutdown();
            if (!requestWorkers.awaitTermination(KEEP_ALIVE_TIME, TimeUnit.SECONDS)) {
                requestWorkers.shutdownNow();
            }
        } catch (IOException e) {
            LOGGER.error("Error occurred while closing database");
        } catch (InterruptedException e) {
            LOGGER.error("Error occurred while stopping request workers");
            requestWorkers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        super.stop();
    }

    @Path(DEFAULT_PATH)
    public Response entity(Request request, @Param(value = "id", required = true) String id) {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                return executeRequest("GET", () -> {
                    Entry<MemorySegment> result = daoWrapper.get(id);
                    if (result != null && result.value() != null) {
                        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
                    }
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                });
            }

            case Request.METHOD_PUT -> {
                return executeRequest("PUT", () -> {
                    daoWrapper.upsert(id, request);
                    return new Response(Response.CREATED, Response.EMPTY);
                });
            }

            case Request.METHOD_DELETE -> {
                return executeRequest("DELETE", () -> {
                    daoWrapper.delete(id);
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                });
            }

            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        String id = request.getParameter(ID_REQUEST);
        if (id != null && id.isEmpty()) {
            try {
                handleDefault(request, session);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            try {
                super.handleRequest(request, session);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private Response executeRequest(String method, Callable<Response> request) {
        Future<Response> response = requestWorkers.submit(request);
        try {
            return response.get();
        } catch (InterruptedException e) {
            LOGGER.error(method + "operation was interrupted");
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.error("Error occurred while executing" + method);
        }
        return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
    }

}
