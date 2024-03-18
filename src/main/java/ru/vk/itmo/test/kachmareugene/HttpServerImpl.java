package ru.vk.itmo.test.kachmareugene;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
    private static final Response ACCEPTED = new Response(Response.ACCEPTED, Response.EMPTY);
    Dao<MemorySegment, Entry<MemorySegment>> daoImpl;
    private final ServiceConfig serviceConfig;
    private final String selfNodeURL;
    private static final Response BAD = new Response(Response.BAD_REQUEST, Response.EMPTY);
    private final PartitionMetaInfo partitionTable;
    private final Map<String, HttpClient> clientMap = new HashMap<>();
    private boolean closed = false;


    public HttpServerImpl(ServiceConfig conf) throws IOException {
        super(convertToHttpConfig(conf));
        this.serviceConfig = conf;
        this.partitionTable = new PartitionMetaInfo(conf.clusterUrls(), 1);
        this.selfNodeURL = conf.selfUrl();
    }

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
        try {
            acceptorConfig.port = new URI(conf.selfUrl()).getPort();
        } catch (URISyntaxException ignore) {

        }

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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        super.start();
    }

    private static void closeExecutorService(ExecutorService exec) {
        if (exec == null) {
            return;
        }

        exec.shutdownNow();
        while (!exec.isTerminated()) {
            try {
                if (exec.awaitTermination(20, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getEntry(
            @Param("id") String key) {

        if (key == null || key.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> result = daoImpl.get(MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return new Response("200", result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putOrEmplaceEntry(
            @Param("id") String key,
            Request request) {

        if (key == null || key.isEmpty()) {
            return BAD;
        }

        daoImpl.upsert(
                new BaseEntry<>(MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                                MemorySegment.ofArray(request.getBody())));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param("id") String key) {
        if (key.isEmpty()) {
            return BAD;
        }
        daoImpl.upsert(
                new BaseEntry<>(
                    MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                    null));
        return ACCEPTED;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    String urlToSend = partitionTable.getCorrectURL(request);

                    if (urlToSend.equals(selfNodeURL)) {
                        super.handleRequest(request, session);
                    } else {
                        session.sendResponse(clientMap.get(urlToSend).invoke(request, 100));
                    }
                } catch (RuntimeException e) {
                    errorAccept(session, e, Response.BAD_REQUEST);
                } catch (IOException e) {
                    errorAccept(session, e, Response.CONFLICT);
                } catch (HttpException e) {
                    errorAccept(session, e, Response.NOT_ACCEPTABLE);
                } catch (PoolException e) {
                    errorAccept(session, e, Response.INTERNAL_ERROR);
                } catch (InterruptedException e) {
                    errorAccept(session, e, Response.INTERNAL_ERROR);
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendError(Response.CONFLICT, e.toString());
        }
    }

    private void errorAccept(HttpSession session, Exception e, String message) {
        try {
            session.sendError(message, e.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        int method = request.getMethod();
        Response response;
        if (method == Request.METHOD_PUT
                || method == Request.METHOD_DELETE
                || method == Request.METHOD_GET) {

            response = BAD;
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
            closeExecutorService(executorService);
            daoImpl.close();
            for (final HttpClient client: clientMap.values()) {
                client.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
