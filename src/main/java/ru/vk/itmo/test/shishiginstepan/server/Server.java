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
import ru.vk.itmo.test.shishiginstepan.dao.EntryWithTimestamp;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Server extends HttpServer {
    private final DistributedDao dao;
    private static final String BASE_PATH = "/v0/entity";
    private static final String TIMESTAMP_HEADER = "X-timestamp: ";
    private static final String INNER_REQUEST_HEADER = "X-inner-request: ";

    private final Logger logger = Logger.getLogger("lsm-db-server");

    private final ExecutorService executor;
    private static final Duration defaultTimeout = Duration.of(200, ChronoUnit.MILLIS);
    private static final ZoneId ServerZoneId = ZoneId.of("+0");

    private static final ThreadFactory threadFactory = new ThreadFactory() {
        private final AtomicInteger workerNamingCounter = new AtomicInteger(0);

        @Override
        public Thread newThread(@Nonnull Runnable r) {
            return new Thread(r, "lsm-db-worker-" + workerNamingCounter.getAndIncrement());
        }
    };

    public Server(ServiceConfig config, DistributedDao dao) throws IOException {
        super(configFromServiceConfig(config));
        this.dao = dao;
        //TODO подумать какое значение будет разумным
        BlockingQueue<Runnable> requestQueue = new ArrayBlockingQueue<>(100);
        int processors = Runtime.getRuntime().availableProcessors();
        this.executor = new ThreadPoolExecutor(
                processors,
                processors,
                32,
                TimeUnit.SECONDS,
                requestQueue,
                threadFactory
        );
    }

    private static HttpServerConfig configFromServiceConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
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
                    try {
                        handleRequestInOtherThread(request, session, requestExpirationDate);
                    } catch (IOException e) {
                        session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));

                    } catch (HttpException | DistributedDao.ClusterLimitExceeded e) {
                        session.sendError(Response.BAD_REQUEST, null);

                    } catch (DistributedDao.NoConsensus e) {
                        session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));

                    }
                } catch (IOException exceptionHandlingException) {
                    logger.error(exceptionHandlingException.initCause(exceptionHandlingException));
                    session.scheduleClose();
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
    public Response getOne(
            @Param(value = "id", required = true) String id,
            @Param(value = "ack") String ack,
            @Param(value = "from") String from,
            Request request
    ) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        EntryWithTimestamp<MemorySegment> entry;

        if (request.getHeader(INNER_REQUEST_HEADER) != null) {
            entry = dao.get(key);
        } else {
            entry = dao.get(
                    key,
                    from == null ? null : Integer.parseInt(from),
                    ack == null ? null : Integer.parseInt(ack)
            );
        }

        if (entry.value() == null) {
            Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(TIMESTAMP_HEADER + entry.timestamp());
            return response;
        }
        Response response = new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
        response.addHeader(TIMESTAMP_HEADER + entry.timestamp());
        return response;
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putOne(
            @Param(value = "id", required = true) String id,
            @Param(value = "ack") String ack,
            @Param(value = "from") String from,
            Request request
    ) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        MemorySegment val = MemorySegment.ofArray(request.getBody());
        if (request.getHeader(INNER_REQUEST_HEADER) == null) {
            dao.upsert(
                    new EntryWithTimestamp<>(
                            key,
                            val,
                            System.currentTimeMillis()),
                    from == null ? null : Integer.parseInt(from),
                    ack == null ? null : Integer.parseInt(ack)
            );
        } else {
            var timestamp = getTimestampHeaderValue(request);
            if (timestamp != null) {
                dao.upsert(
                        new EntryWithTimestamp<>(
                                key,
                                val,
                                timestamp
                        )
                );
            }
        }
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(BASE_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteOne(
            @Param(value = "id", required = true) String id,
            @Param(value = "ack") String ack,
            @Param(value = "from") String from,
            Request request
    ) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        if (request.getHeader(INNER_REQUEST_HEADER) == null) {
            dao.upsert(
                    new EntryWithTimestamp<>(
                            key,
                            null,
                            System.currentTimeMillis()),
                    from == null ? null : Integer.parseInt(from),
                    ack == null ? null : Integer.parseInt(ack)
            );
        } else {
            var timestamp = getTimestampHeaderValue(request);
            if (timestamp != null) {
                dao.upsert(
                        new EntryWithTimestamp<>(
                                key,
                                null,
                                timestamp
                        )
                );
            }
        }

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static Long getTimestampHeaderValue(Request request) {
        var headerRaw = request.getHeader(TIMESTAMP_HEADER).substring(2);
        if (headerRaw == null) {
            return null;
        }
        return Long.parseLong(headerRaw);
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

    @Override
    public synchronized void stop() {
        super.stop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    logger.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
