package ru.vk.itmo.test.kachmareugene;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.kachmareugene.dao.BaseTimestampEntry;
import ru.vk.itmo.test.kachmareugene.dao.EntryWithTimestamp;
import ru.vk.itmo.test.kachmareugene.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HttpServerImpl extends HttpServer {

    private static final int CORE_POOL = 4;
    private static final int MAX_POOL = 8;
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(256);
    private final ExecutorService executorService =
            new ThreadPoolExecutor(CORE_POOL, MAX_POOL,
                        10L, TimeUnit.MILLISECONDS,
                                    queue);
    Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> daoImpl;
    private final String selfNodeURL;
    private final ServiceConfig serviceConfig;
    private final PartitionMetaInfo partitionTable;
    private final Map<String, HttpClient> clientMap = new HashMap<>();
    private boolean closed;

    public HttpServerImpl(ServiceConfig config, PartitionMetaInfo info) throws IOException {
        super(convertToHttpConfig(config));
        this.serviceConfig = config;
        this.partitionTable = info;
        this.selfNodeURL = config.selfUrl();

        for (final String url: config.clusterUrls()) {
            clientMap.put(url, new HttpClient(new ConnectionString(url)));
        }
    }

    private static HttpServerConfig convertToHttpConfig(ServiceConfig conf) throws UncheckedIOException {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = conf.selfPort();

        acceptorConfig.reusePort = true;

        HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.closeSessions = true;
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        return httpServerConfig;
    }

    @Override
    public synchronized void start() {
        try {
            daoImpl = new ReferenceDao(new Config(serviceConfig.workingDir(), 16384));
            this.closed = false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        super.start();
    }

    public Response getEntry(String key) {

        if (key == null || key.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Utils.longToBytes(0L));
        }

        EntryWithTimestamp<MemorySegment> result =
                daoImpl.get(MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Utils.longToBytes(0L));
        }

        byte[] time = Utils.longToBytes(result.timestamp());

        if (result.value() == null) {
            return new Response(Response.NOT_FOUND, time);
        }

        byte[] value = result.value().toArray(ValueLayout.JAVA_BYTE);
        byte[] responseBody = new byte[value.length + time.length];

        System.arraycopy(time, 0, responseBody, 0, time.length);
        System.arraycopy(value, 0, responseBody, time.length, value.length);

        return new Response("200", responseBody);
    }

    public Response putOrEmplaceEntry(String key, Request request, long timestamp) {
        if (key == null || key.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        daoImpl.upsert(new BaseTimestampEntry<>(
                MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(request.getBody()),
                timestamp));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response delete(String key, long timestamp) {
        if (key.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        daoImpl.upsert(
                new BaseTimestampEntry<>(
                        MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                        null,
                        timestamp));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (extracted(request, session)) return;

        try {
            executorService.execute(() -> {
                try {
                    if (extracted1(request, session)) return;
                } catch (RuntimeException e) {
                    errorAccept(session, e, Response.BAD_REQUEST);
                } catch (IOException e) {
                    errorAccept(session, e, Response.CONFLICT);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorAccept(session, e, Response.CONFLICT);
                } catch (HttpException e) {
                    errorAccept(session, e, Response.NOT_ACCEPTABLE);
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendError(Response.CONFLICT, e.toString());
        }
    }

    private boolean extracted1(Request request, HttpSession session) throws IOException,
            InterruptedException,
            HttpException {
        if (request.getHeader("X-Timestamp", null) != null) {
            session.sendResponse(handleToDaoOperations(request,
                    System.currentTimeMillis()));
            return true;
        }

        String urlToSend = partitionTable.getCorrectURL(request);

        Coordinator coordinator = new Coordinator();
        coordinator.getCoordinatorParams(request, serviceConfig);

        List<String> slaves = partitionTable.getSlaveUrls(request, coordinator.from);
        List<Response> responses = new ArrayList<>();

        if (urlToSend.equals(selfNodeURL)) {
            responses.add(handleToDaoOperations(request,
                    System.currentTimeMillis()));
        } else {
            slaves.addFirst(urlToSend);
        }

        for (String slaveUrl : slaves) {
            Request req = new Request(request);
            req.addHeader("X-Timestamp: " + System.currentTimeMillis());
            extracted(slaveUrl, responses, req);
        }

        session.sendResponse(
                coordinator.resolve(responses, request.getMethod()));
        return false;
    }

    private static boolean extracted(Request request, HttpSession session) throws IOException {
        String path = request.getPath();
        if (!path.equals("/v0/entity")) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return true;
        }

        String key = request.getParameter("id=");
        if (key == null || key.isEmpty()) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return true;
        }
        return false;
    }

    private void extracted(String slaveUrl, List<Response> responses, Request req) throws InterruptedException,
            IOException,
            HttpException {
        try {
            responses.add(clientMap.get(slaveUrl).invoke(req, 100));
        } catch (PoolException e) {
            responses.add(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
        }
    }

    private void errorAccept(HttpSession session, Exception e, String message) {
        try {
            session.sendError(message, e.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public Response handleToDaoOperations(Request request, long timestamp) {
        String key = request.getParameter("id");

        int m = request.getMethod();
        return switch (m) {
            case Request.METHOD_GET -> getEntry(key);
            case Request.METHOD_PUT -> putOrEmplaceEntry(key, request, timestamp);
            case Request.METHOD_DELETE -> delete(key, timestamp);
            default -> new Response(Response.METHOD_NOT_ALLOWED);
        };
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        int method = request.getMethod();
        Response response;
        if (method == Request.METHOD_PUT
                || method == Request.METHOD_DELETE
                || method == Request.METHOD_GET) {

            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        if (closed) {
            return;
        }
        closed = true;
        super.stop();
        try {
            Utils.closeExecutorService(executorService);
            daoImpl.close();
            for (final HttpClient client: clientMap.values()) {
                client.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
