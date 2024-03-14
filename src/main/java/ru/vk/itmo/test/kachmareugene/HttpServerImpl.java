package ru.vk.itmo.test.kachmareugene;

import one.nio.http.*;
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HttpServerImpl extends HttpServer {

    private static final int CORE_POOL = 4;
    private static final int MAX_POOL = 8;
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(50);
    private final ExecutorService executorService =
            new ThreadPoolExecutor(CORE_POOL, MAX_POOL,
                        10L, TimeUnit.MILLISECONDS,
                                    queue);
    private static final Response ACCEPTED = new Response(Response.ACCEPTED, Response.EMPTY);
    Dao<MemorySegment, Entry<MemorySegment>> daoImpl;
    private final ServiceConfig serviceConfig;
    private static final Response BAD = new Response(Response.BAD_REQUEST, Response.EMPTY);

    private final PartitionMetaInfo partitionTable;
    public HttpServerImpl(ServiceConfig conf) throws IOException {
        super(convertToHttpConfig(conf, 0));
        this.serviceConfig = conf;
        this.partitionTable = new PartitionMetaInfo(conf.clusterUrls(), 1);
    }

    public HttpServerImpl(ServiceConfig config, PartitionMetaInfo info, int ind) throws IOException {
        super(convertToHttpConfig(config, ind));
        this.serviceConfig = config;
        this.partitionTable = info;

    }

    private static HttpServerConfig convertToHttpConfig(ServiceConfig conf, int ind) throws UncheckedIOException {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = Integer.parseInt(conf.clusterUrls().get(ind).split(":")[2]);

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

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            closeExecutorService(executorService);
            daoImpl.close();

        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        System.err.println("in");
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
        daoImpl.upsert(new BaseEntry<>(MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)),
                        null));
        System.err.println("deleted");
        return ACCEPTED;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {

            executorService.execute(() -> {
                try {
                    int portFromReq = partitionTable.getRealPortWithDataByRequest(request);
                    if (portFromReq == port) {
                        System.err.println("make " + request.getMethodName() + request.getURI() + "port"+portFromReq);

                        super.handleRequest(request, session);
                    } else {
                        String oldHostName = request.getHeader("Host:").split(":")[0];
                        String newHost =
                                STR."Host: \{String.join(":", oldHostName, String.valueOf(portFromReq))}";

                        Request request_ver2 = getRequest_ver2(request, newHost);

                        String clientURL = STR."http://\{request_ver2.getHeader("Host:")}\{request_ver2.getURI()}";

                        try (HttpClient client =
                                     new HttpClient(new ConnectionString(clientURL))){
                            System.err.println("redirect " + request_ver2.getMethodName() + request_ver2.getURI()
                                    + "port"+portFromReq);
                            session.sendResponse(client.invoke(request_ver2));
                        }
                    }
                } catch (RuntimeException e) {
                    errorAccept(session, e, Response.BAD_REQUEST);
                } catch (IOException e) {
                    errorAccept(session, e, Response.CONFLICT);
                } catch (HttpException e) {
                    errorAccept(session, e, Response.NOT_ACCEPTABLE);
                } catch (PoolException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendError(Response.CONFLICT, e.toString());
        }
    }

    private static Request getRequest_ver2(Request request, String newHost) {
        Request request_ver2 = new Request(request.getMethod(), request.getURI(), request.isHttp11());
        request_ver2.setBody(request.getBody());

        for (var h : request.getHeaders()) {
            if (h != null) {
                if (h.startsWith("Host")) {
                    request_ver2.addHeader(newHost);
                } else {
                    request_ver2.addHeader(h);
                }
            }
        }
        return request_ver2;
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
}
