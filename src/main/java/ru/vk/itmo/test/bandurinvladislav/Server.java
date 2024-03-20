package ru.vk.itmo.test.bandurinvladislav;

import one.nio.http.HttpClient;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.util.Hash;
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
                .forEach(u -> {
                    HttpClient client = new HttpClient(new ConnectionString(u));
                    client.setTimeout(100);
                    clients.put(u, client);
                });
        logger.info("Server started");
    }

    public Response getEntity(@Param(value = "id", required = true) String id) {
        Entry<MemorySegment> result = dao.get(MemSegUtil.fromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        MemorySegment.ofArray(request.getBody())
                )
        );
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response deleteEntity(@Param(value = "id", required = true) String id) {
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

        HttpClient client = getClientByKey(key);
        if (client == null) {
            invokeLocal(request, session, key);
        } else {
            invokeRemote(request, session, client);
        }
    }

    private void invokeLocal(Request request, HttpSession session, String key) throws IOException {
        Response response;
        try {
            response = switch (request.getMethod()) {
                case Request.METHOD_GET -> getEntity(key);
                case Request.METHOD_PUT -> putEntity(key, request);
                case Request.METHOD_DELETE -> deleteEntity(key);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (Exception e) {
            logger.error("Internal error during request handling: " + e.getMessage());
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        session.sendResponse(response);
    }

    private void invokeRemote(Request request, HttpSession session, HttpClient client) throws IOException {
        Response response;
        try {
            response = switch (request.getMethod()) {
                case Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE -> client.invoke(request);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (Exception e) {
            logger.error("Internal error during request handling: " + e.getMessage());
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    private HttpClient getClientByKey(String key) {
        String selectedNode = serverConfig.clusterUrls.getFirst();
        int maxHash = Hash.murmur3(selectedNode + key);

        for (int i = 1; i < serverConfig.clusterUrls.size(); i++) {
            String currentNodeUrl = serverConfig.clusterUrls.get(i);
            int currentHash = Hash.murmur3(currentNodeUrl + key);

            if (currentHash > maxHash) {
                maxHash = currentHash;
                selectedNode = currentNodeUrl;
            }
        }

        return clients.get(selectedNode);
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
