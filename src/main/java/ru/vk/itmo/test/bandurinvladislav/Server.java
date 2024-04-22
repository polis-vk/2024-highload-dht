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
import ru.vk.itmo.test.bandurinvladislav.config.DhtServerConfig;
import ru.vk.itmo.test.bandurinvladislav.dao.BaseEntry;
import ru.vk.itmo.test.bandurinvladislav.dao.Config;
import ru.vk.itmo.test.bandurinvladislav.dao.Entry;
import ru.vk.itmo.test.bandurinvladislav.dao.ReferenceDao;
import ru.vk.itmo.test.bandurinvladislav.util.Constants;
import ru.vk.itmo.test.bandurinvladislav.util.MemSegUtil;
import ru.vk.itmo.test.bandurinvladislav.util.NetworkUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Server extends HttpServer {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final Map<String, HttpClient> clients;
    private final int clusterSize;
    private final DaoWorkerPool workerPool;
    private final DhtServerConfig serverConfig;

    private final ReferenceDao dao;

    public Server(DhtServerConfig config, java.nio.file.Path workingDir) throws IOException {
        super(config);
        workerPool = new DaoWorkerPool(
                Constants.ACCEPTOR_THREADS,
                Constants.ACCEPTOR_THREADS,
                Constants.THREAD_KEEP_ALIVE_TIME,
                TimeUnit.SECONDS
        );

        serverConfig = config;
        workerPool.prestartAllCoreThreads();
        Config daoConfig = new Config(workingDir, Constants.FLUSH_THRESHOLD_BYTES);
        dao = new ReferenceDao(daoConfig);
        clients = new HashMap<>();
        clusterSize = serverConfig.clusterUrls.size();
        config.clusterUrls.stream()
                .filter(url -> !url.equals(config.selfUrl))
                .forEach(u -> {
                    HttpClient client = new HttpClient(new ConnectionString(u));
                    client.setTimeout(Constants.CLIENT_RESPONSE_TIMEOUT_MILLIS);
                    clients.put(u, client);
                });
        logger.info("Server started");
    }

    public Response getEntity(@Param(value = "id", required = true) String id) {
        Entry<MemorySegment> result = dao.get(MemSegUtil.fromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        Response response;
        if (result.value() == null) {
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
        } else {
            response = Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
        }
        response.addHeader(Constants.HEADER_TIMESTAMP + result.timestamp());
        return response;
    }

    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        MemorySegment.ofArray(request.getBody()),
                        System.currentTimeMillis()
                )
        );
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        null,
                        System.currentTimeMillis()
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

    @SuppressWarnings("unused")
    private void handleDaoCall(Request request, HttpSession session) throws IOException {
        String path = request.getPath();
        if (!path.equals(Constants.ENDPOINT)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        if (!NetworkUtil.isMethodAllowed(request)) {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
            return;
        }

        int ack = NetworkUtil.getParameterAsInt(request.getParameter(Constants.PARAMETER_ACK),
                clusterSize / 2 + clusterSize % 2);
        int from = NetworkUtil.getParameterAsInt(
                request.getParameter(Constants.PARAMETER_FROM), clusterSize);
        String key = request.getParameter(Constants.PARAMETER_ID);

        Response validationResponse = NetworkUtil.validateParams(key, ack, from, clusterSize);
        if (validationResponse != null) {
            session.sendResponse(validationResponse);
            return;
        }

        String sender = request.getHeader(Constants.HEADER_SENDER);
        if (sender != null) {
            session.sendResponse(invokeLocal(request, key));
            return;
        } else {
            request.addHeader(Constants.HEADER_SENDER + serverConfig.clusterUrls);
        }

        List<HttpClient> sortedClients = getClientsByKey(key, from);
        RequestProcessingState rs = new RequestProcessingState(from);
        for (HttpClient client : sortedClients) {
            if (client == null) {
                Response response = invokeLocal(request, key);
                NetworkUtil.handleResponse(session, rs, response, ack, from);
                continue;
            }
            CompletableFuture<Response> remote = CompletableFuture.supplyAsync(
                    () -> invokeRemote(request, client), workerPool)
                    .completeOnTimeout(
                            new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY), 100, TimeUnit.MILLISECONDS);
            CompletableFuture<Void> responseAction = remote.thenAccept(r -> {
                if (r.getStatus() == HttpURLConnection.HTTP_INTERNAL_ERROR
                        || r.getStatus() == HttpURLConnection.HTTP_GATEWAY_TIMEOUT) {
                    NetworkUtil.handleTimeout(session, rs, ack, from);
                } else {
                    NetworkUtil.handleResponse(session, rs, r, ack, from);
                }
            });
        }
    }

    private Response invokeLocal(Request request, String key) {
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

        return response;
    }

    private static Response invokeRemote(Request request, HttpClient client) {
        Response response;
        try {
            response = client.invoke(request);
        } catch (Exception e) {
            logger.error("Internal error during request handling: " + e.getMessage());
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return response;
    }

    private List<HttpClient> getClientsByKey(String key, int from) {
        TreeMap<Integer, String> hashUrlSorted = new TreeMap<>();

        for (int i = 0; i < clusterSize; i++) {
            String currentNodeUrl = serverConfig.clusterUrls.get(i);
            int currentHash = Hash.murmur3(currentNodeUrl + key);
            hashUrlSorted.put(currentHash, currentNodeUrl);
        }

        List<HttpClient> res = new ArrayList<>();
        Iterator<String> iterator = hashUrlSorted.values().iterator();
        while (from-- > 0) {
            res.add(clients.get(iterator.next()));
        }
        return res;
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
