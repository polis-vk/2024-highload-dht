package ru.vk.itmo.test.dariasupriadkina;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Server extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(Server.class.getName());
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private final Set<Integer> permittedMethods =
            Set.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE);
    private final String selfUrl;
    private final ShardingPolicy shardingPolicy;
    public static final String ENTRY_PREFIX = "/v0/entity";
    public static final String ENTRY_PREFIX_WITH_ID_PARAM = ENTRY_PREFIX + "?id=" ;
    private final HttpClient httpClient;

//    private final long HTTP_REQUEST_TIMEOUT_SEC = 10;

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao,
                  ThreadPoolExecutor executorService, ShardingPolicy shardingPolicy)
            throws IOException {
        super(createHttpServerConfig(config));
        this.dao = dao;
        this.executorService = executorService;
        this.shardingPolicy = shardingPolicy;
        this.selfUrl = config.selfUrl();
        ThreadPoolExecutor nodeExecutor = new ThreadPoolExecutor(8,
                8,
                0L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1024),
                new CustomThreadFactory("node-executor", true));
        this.httpClient = HttpClient.newBuilder().executor(nodeExecutor).build();
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();

        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        httpServerConfig.closeSessions = true;

        return httpServerConfig;
    }

    @Path("/health")
    @RequestMethod(Request.METHOD_GET)
    public Response health() {
        return Response.ok(Response.OK);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getHandler(@Param(value = "id", required = true) String id, Request request) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            if (!shardingPolicy.getUrlById(id).equals(selfUrl)) {
                return handleProxy(getRedirectedUrl(id), request);
            }
            Entry<MemorySegment> entry = getEntryById(id);
            if (entry == null || entry.value() == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }
            return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putHandler(Request request, @Param(value = "id", required = true) String id) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            if (!shardingPolicy.getUrlById(id).equals(selfUrl)) {
                return handleProxy(getRedirectedUrl(id), request);
            }
            dao.upsert(convertToEntry(id, request.getBody()));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteHandler(@Param(value = "id", required = true) String id, Request request) {
        try {
            if (id.isEmpty()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            if (!shardingPolicy.getUrlById(id).equals(selfUrl)) {
                return handleProxy(getRedirectedUrl(id), request);
            }
            dao.upsert(convertToEntry(id, null));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (Exception e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response;
        if (permittedMethods.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);

        }
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    logger.error("Unexpected error", e);
                    try {
                        if (e.getClass() == HttpException.class) {
                            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                        }
                    } catch (IOException ex) {
                        logger.error("Failed to send error response", e);
                        session.close();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("Service is unavailable", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));

        }
    }

    public Response handleProxy(String redirectedUrl, Request request) {
        try {
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(redirectedUrl))
                    .method(request.getMethodName(), HttpRequest.BodyPublishers.ofByteArray(
                            request.getBody() == null ? new byte[]{} : request.getBody())
                    )
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(String.valueOf(response.statusCode()), response.body());
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private String getRedirectedUrl(String id) {
        return shardingPolicy.getUrlById(id) + ENTRY_PREFIX_WITH_ID_PARAM + id;
    }

    private Entry<MemorySegment> convertToEntry(String id, byte[] body) {
        return new BaseEntry<>(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                convertByteArrToMemorySegment(body));
    }

    private MemorySegment convertByteArrToMemorySegment(byte[] bytes) {
        return bytes == null ? null : MemorySegment.ofArray(bytes);
    }

    private Entry<MemorySegment> getEntryById(String id) {
        return dao.get(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)));
    }
}
