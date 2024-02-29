package ru.vk.itmo.test.tarazanovmaxim;

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
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.tarazanovmaxim.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyServer extends HttpServer {

    private final ReferenceDao dao;
    private static final long FLUSH_THRESHOLD_BYTES = 1 << 20;
    private static final String PATH = "/v0/entity";
    private static final long REQUEST_TTL = TimeUnit.SECONDS.toNanos(100);
    private final ExecutorService executorService;
    private static final Logger logger = LoggerFactory.getLogger(MyServer.class);

    public MyServer(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));

        int corePoolSize = Runtime.getRuntime().availableProcessors() / 2 + 1;
        long keepAliveTime = 0L;
        int queueCapacity = 100;

        executorService = new ThreadPoolExecutor(
                corePoolSize,
                corePoolSize,
                keepAliveTime,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();

        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;

        return httpServerConfig;
    }

    private static MemorySegment toMemorySegment(String string) {
        return MemorySegment.ofArray(string.getBytes(StandardCharsets.UTF_8));
    }

    public void close() throws IOException {
        executorService.close();
        dao.close();
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_GET)
    public final Response get(@Param(value = "id", required = true) String id) {
        MemorySegment key =
                (id == null || id.isEmpty())
                ? null
                : toMemorySegment(id);

        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_PUT)
    public final Response put(@Param(value = "id", required = true) String id, Request request) {
        MemorySegment key =
                (id == null || id.isEmpty())
                ? null
                : toMemorySegment(id);

        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Entry<MemorySegment> entry = new BaseEntry<>(
                key,
                MemorySegment.ofArray(request.getBody())
        );

        dao.upsert(entry);

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public final Response delete(@Param(value = "id", required = true) String id) {
        MemorySegment key =
                (id == null || id.isEmpty())
                ? null
                : toMemorySegment(id);

        if (key == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        dao.upsert(new BaseEntry<>(key, null));

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(PATH)
    public Response otherMethod() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        sendResponse(response, session);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        try {
            long startTime = System.nanoTime();
            executorService.execute(() -> {
                if (System.nanoTime() > startTime + REQUEST_TTL) {
                    sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY), session);
                    return;
                }

                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    logger.error("IOException in handleRequest->executorService.execute()");
                    sendResponse(
                        new Response(
                            e.getClass() == IOException.class ? Response.INTERNAL_ERROR : Response.BAD_REQUEST,
                            Response.EMPTY
                        ),
                        session
                    );
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error("RejectedExecutionException in handleRequest: " + request + session);
            sendResponse(new Response("429 Too Many Requests", Response.EMPTY), session);
        }
    }

    public void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("IOException in sendResponse: " + response + session);
        }
    }
}
