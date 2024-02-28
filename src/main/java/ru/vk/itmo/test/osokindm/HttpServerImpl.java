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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HttpServerImpl extends HttpServer {

    private static final String DEFAULT_PATH = "/v0/entity";
    private static final String ID_REQUEST = "id=";
    private static final Logger logger = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ExecutorService requestWorkers;
    private final DaoWrapper daoWrapper;


    public HttpServerImpl(ServiceConfig config, DaoWrapper daoWrapper) throws IOException {
        super(createServerConfig(config));
        this.daoWrapper = daoWrapper;
        requestWorkers = new ThreadPoolExecutor(64, 64, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    }

    @Override
    public synchronized void stop() {
        try {
            daoWrapper.stop();
            requestWorkers.close();
        } catch (IOException e) {
            logger.error("Error occurred while closing database");
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
                Future<Response> response = requestWorkers.submit(() -> {
                    Entry<MemorySegment> result = daoWrapper.get(id);
                    if (result != null && result.value() != null) {
                        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
                    }
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                });
                try {
                    return response.get();
                } catch (InterruptedException e) {
                    logger.error("GET operation was interrupted");
                } catch (ExecutionException e) {
                    logger.error("Error occurred while executing GET");
                }
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }

            case Request.METHOD_PUT -> {
                Future<Response> response = requestWorkers.submit(() -> {
                    daoWrapper.upsert(id, request);
                    return new Response(Response.CREATED, Response.EMPTY);
                });
                try {
                    return response.get();
                } catch (InterruptedException e) {
                    logger.error("UPSERT operation was interrupted");
                } catch (ExecutionException e) {
                    logger.error("Error occurred while executing UPSERT");
                }
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }

            case Request.METHOD_DELETE -> {
                Future<Response> response = requestWorkers.submit(() -> {
                    daoWrapper.delete(id);
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                });
                try {
                    return response.get();
                } catch (InterruptedException e) {
                    logger.error("DELETE operation was interrupted");
                } catch (ExecutionException e) {
                    logger.error("Error occurred while executing DELETE");
                }
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }

            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
//        requestWorkers.execute(() -> {
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
//        });
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

}
