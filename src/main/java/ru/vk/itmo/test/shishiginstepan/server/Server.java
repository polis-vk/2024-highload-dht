package ru.vk.itmo.test.shishiginstepan.server;

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
import org.apache.log4j.Logger;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Server extends HttpServer {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String BASE_PATH = "/v0/entity";

    private final Logger logger = Logger.getLogger("lsm-db-server");

    private final Executor executor;
    private static final Duration defaultTimeout = Duration.of(200, ChronoUnit.MILLIS);
    private static final ZoneId ServerZoneId = ZoneId.of("+0");

    private static final ThreadFactory threadFactory = new ThreadFactory() {
        private final AtomicInteger workerNamingCounter = new AtomicInteger(0);

        @Override
        public Thread newThread(@Nonnull Runnable r) {
            return new Thread(r, "lsm-db-worker-" + workerNamingCounter.getAndIncrement());
        }
    };

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(configFromServiceConfig(config));
        this.dao = dao;
        //TODO подумать какое значение будет разумным
        BlockingQueue<Runnable> requestQueue = new ArrayBlockingQueue<>(100);
        int processors = Runtime.getRuntime().availableProcessors();
        this.executor = new ThreadPoolExecutor(
                this.getSelectorCount(),
                Math.min(this.getSelectorCount() * 2, processors),
                32,
                TimeUnit.SECONDS,
                requestQueue,
                threadFactory
        );
    }

    private static HttpServerConfig configFromServiceConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new one.nio.server.AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = serviceConfig.selfPort();

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        LocalDateTime requestExpirationDate = LocalDateTime.now(ServerZoneId).plus(defaultTimeout);
        try {
            executor.execute(() -> {
                try {
                    handleRequestInOtherThread(request, session, requestExpirationDate);
                } catch (IOException | HttpException e) {
                    try {
                        if (e instanceof HttpException) {
                            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                        }
                    } catch (IOException exceptionHandlingException) {
                        logger.error(exceptionHandlingException.initCause(e));
                        session.close();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error(e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    private void handleRequestInOtherThread(
            Request request,
            HttpSession session,
            LocalDateTime requestExpirationDate
    ) throws IOException, HttpException {
        if (LocalDateTime.now(ServerZoneId).isAfter(requestExpirationDate)) {
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        } else {
            super.handleRequest(request, session);
        }
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_GET)

    public Response getOne(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        Entry<MemorySegment> val;
        try {
            val = dao.get(key);
        } catch (Exception e) {
            logger.error(e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        if (val == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(val.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putOne(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        MemorySegment val = MemorySegment.ofArray(request.getBody());
        try {
            dao.upsert(new BaseEntry<>(key, val));
        } catch (Exception e) {
            logger.error(e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteOne(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        try {
            dao.upsert(new BaseEntry<>(key, null));
        } catch (Exception e) {
            logger.error(e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(BASE_PATH)
    public Response notAllowed() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }
}
