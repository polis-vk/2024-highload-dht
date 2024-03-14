package ru.vk.itmo.test.bandurinvladislav;

import one.nio.http.HttpClient;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.bandurinvladislav.config.DhtServerConfig;
import ru.vk.itmo.test.bandurinvladislav.dao.ReferenceDao;
import ru.vk.itmo.test.bandurinvladislav.util.Constants;
import ru.vk.itmo.test.bandurinvladislav.util.MemSegUtil;
import ru.vk.itmo.test.bandurinvladislav.util.StringUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Server extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final Map<String, HttpClient> clients;
    private final DaoWorkerPool workerPool;
    private final DhtServerConfig serverConfig;
    private final ReferenceDao dao;

    public Server(DhtServerConfig config, java.nio.file.Path workingDir) throws IOException {
        super(config);
        workerPool = new DaoWorkerPool(
                Constants.THREADS,
                Constants.THREADS,
                60,
                TimeUnit.SECONDS
        );
        serverConfig = config;
        workerPool.prestartAllCoreThreads();
        Config daoConfig = new Config(workingDir, Constants.FLUSH_THRESHOLD_BYTES);
        dao = new ReferenceDao(daoConfig);
        clients = new HashMap<>();
        config.clusterUrls.stream()
                .filter(url -> !url.equals(config.selfUrl))
                .forEach(u -> clients.put(u, new HttpClient(new ConnectionString(u))));
        logger.info("Server started");
    }


    public Response getEntity(@Param(value = "id", required = true) String id, HttpClient client) {
        if (client != null) {
            try {
                return client.get(Constants.ENDPOINT + "?id=" + id);
            } catch (Exception e) {
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
        }

        Entry<MemorySegment> result = dao.get(MemSegUtil.fromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response putEntity(@Param(value = "id", required = true) String id, Request request, HttpClient client) {
        if (client != null) {
            try {
                return client.put(Constants.ENDPOINT + "?id=" + id, request.getBody());
            } catch (Exception e) {
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
        }

        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        MemorySegment.ofArray(request.getBody())
                )
        );
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response deleteEntity(@Param(value = "id", required = true) String id, HttpClient client) {
        if (client != null) {
            try {
                return client.delete(Constants.ENDPOINT + "?id=" + id);
            } catch (Exception e) {
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
        }
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        null
                )
        );
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            workerPool.execute(() -> {
                try {
                    handleDaoCall(request, session);
                } catch (IOException e) {
                    logger.error("IO exception during request handling: " + e.getMessage());
                    try {
                        session.sendError(Response.INTERNAL_ERROR, null);
                    } catch (IOException ex) {
                        logger.error("Exception while sending close connection:", e);
                        session.scheduleClose();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            logger.info("queue is full");
            if (workerPool.isShutdown()) {
                session.sendError(Response.SERVICE_UNAVAILABLE, null);
                return;
            }
            session.sendError(Constants.TOO_MANY_REQUESTS, null);
        }
    }

    private void handleDaoCall(Request request, HttpSession session) throws IOException {
        String path = request.getPath();
        if (!path.equals(Constants.ENDPOINT)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        String key = request.getParameter(Constants.PARAMETER_ID);
        if (StringUtil.isEmpty(key)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        HttpClient client = getClient(key);
        Response response;
        try {
            response = switch (request.getMethod()) {
                case Request.METHOD_GET -> getEntity(key, client);
                case Request.METHOD_PUT -> putEntity(key, request, client);
                case Request.METHOD_DELETE -> deleteEntity(key, client);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (Exception e) {
            logger.error("Internal error during request handling: " + e.getMessage());
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        session.sendResponse(response);
    }

    private HttpClient getClient(String key) {
        int size = serverConfig.clusterUrls.size();
        int nodeNumber = key.hashCode() % size;
        nodeNumber = nodeNumber < 0 ? nodeNumber + size : nodeNumber;
        return clients.get(serverConfig.clusterUrls.get(nodeNumber));
    }

    @Override
    public synchronized void stop() {
        super.stop();

        workerPool.gracefulShutdown();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
