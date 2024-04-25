
package ru.vk.itmo.test.kachmareugene;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
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
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HttpServerImpl extends HttpServer {

    private static final int CORE_POOL = 4;
    private static final int MAX_POOL = 8;
    public static final String PATH = "/v0/entity";

    public static final RangeAnswer rangeHandler = new RangeAnswer();

    public static final long KEEP_ALIVE_TIME = 10L;
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(256);
    private final ExecutorService executorService =
            new ThreadPoolExecutor(CORE_POOL, MAX_POOL,
                    KEEP_ALIVE_TIME, TimeUnit.MILLISECONDS,
                    queue);
    Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> daoImpl;
    private final String selfNodeURL;
    private final ServiceConfig serviceConfig;
    private final PartitionMetaInfo partitionTable;
    private final HttpClient client;
    private boolean closed;

    public HttpServerImpl(ServiceConfig config, PartitionMetaInfo info, HttpClient client) throws IOException {
        super(convertToHttpConfig(config));
        this.serviceConfig = config;
        this.partitionTable = info;
        this.client = client;
        this.selfNodeURL = config.selfUrl();
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

        return new Response(Response.OK, responseBody);
    }

    public Response putOrEmplaceEntry(String key, Request request, long timestamp) {
        daoImpl.upsert(new BaseTimestampEntry<>(
                MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(request.getBody()),
                timestamp));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response delete(String key, long timestamp) {
        daoImpl.upsert(
                new BaseTimestampEntry<>(
                        MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                        null,
                        timestamp));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (request.getPath().equals(RangeAnswer.RANGE_REQUEST_PATH)) {
            rangeHandler.handleRange(request, session, daoImpl);
            session.close();
            return;
        }

        if (checkRequest(request, session)) return;

        try {
            executorService.execute(() -> {
                try {
                    task(request, session);
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

    @SuppressWarnings("FutureReturnValueIgnored")
    private void task(Request request,
                      HttpSession session)
            throws IOException,
            InterruptedException,
            HttpException {
        if (request.getHeader(Utils.TIMESTAMP_HEADER, null) != null) {
            session.sendResponse(handleToDaoOperations(request,
                    System.currentTimeMillis()));
            return;
        }

        String urlToSend = partitionTable.getCorrectURL(request);

        Coordinator coordinator = new Coordinator();
        coordinator.getCoordinatorParams(request, serviceConfig);

        List<String> slaves = partitionTable.getSlaveUrls(request, coordinator.from);
        List<CompletableFuture<Response>> receivedResponses = new CopyOnWriteArrayList<>();

        if (urlToSend.equals(selfNodeURL)) {
            CompletableFuture<Response> future = new CompletableFuture<>();
            future.complete(handleToDaoOperations(request,
                    System.currentTimeMillis()));
            receivedResponses.add(future);
        } else {
            slaves.addFirst(urlToSend);
        }

        for (String slaveUrl : slaves) {
            receivedResponses.add(
                    responseSafeAdd(request, slaveUrl, System.currentTimeMillis()));
        }

        // this method is non-blocking
        CompletableFuture.allOf(receivedResponses.toArray(CompletableFuture[]::new))
                .thenApply(ignore -> {
                    var c = receivedResponses
                            .stream()
                            .map(CompletableFuture::join)
                            .toList();
                    try {
                        session.sendResponse(coordinator.resolve(c, request.getMethod()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
    }

    private static boolean checkRequest(Request request, HttpSession session) throws IOException {
        String path = request.getPath();
        if (!path.equals(PATH)) {
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

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<Response> responseSafeAdd(
            Request request,
            String slaveUrl,
            long timestamp) {
        CompletableFuture<Response> cfResponse = new CompletableFuture<>();
        if (slaveUrl.equals(selfNodeURL)) {
            cfResponse.complete(handleToDaoOperations(request,
                    System.currentTimeMillis()));
        } else {
            client.sendAsync(
                            JavaRequestConverter.convertRequest(slaveUrl, request, timestamp),
                            HttpResponse.BodyHandlers.ofByteArray())
                    .completeOnTimeout(null, Utils.TIMEOUT_SECONDS, TimeUnit.MILLISECONDS)
                    .whenComplete((httpResponse, ignore) -> cfResponse.complete(
                            JavaRequestConverter.convertResponse(httpResponse)));
        }
        return cfResponse;
    }

    private void errorAccept(HttpSession session, Exception e, String message) {
        try {
            session.sendError(message, e.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public Response handleToDaoOperations(Request request, long timestamp) {
        String key = request.getParameter("id=");

        int m = request.getMethod();
        return switch (m) {
            case Request.METHOD_GET -> getEntry(key);
            case Request.METHOD_PUT -> putOrEmplaceEntry(key, request, timestamp);
            case Request.METHOD_DELETE -> delete(key, timestamp);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
