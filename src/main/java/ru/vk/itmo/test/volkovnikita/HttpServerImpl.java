package ru.vk.itmo.test.volkovnikita;

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
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpServerImpl extends HttpServer {

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final Logger log = LoggerFactory.getLogger(HttpServerImpl.class);
    private final Executor executor;
    static final Set<String> METHODS = Set.of(
            "GET",
            "PUT",
            "DELETE"
    );
    private static final ZoneId ServerZone = ZoneId.of("UTC");

    public HttpServerImpl(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(createServerConfig(config));
        this.dao = dao;

        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory = r ->
                new Thread(r, "HttpServerImplThread: " + threadCounter.getAndIncrement());

        this.executor = new ThreadPoolExecutor(
                this.getSelectorCount(),
                Runtime.getRuntime().availableProcessors(),
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(3000),
                threadFactory
        );
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Path(value = "/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getEntry(@Param(value = "id", required = true) String id) {
        if (isIdIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        Entry<MemorySegment> entry = dao.get(key);
        return entry == null ? new Response(Response.NOT_FOUND, Response.EMPTY) :
                new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntry(@Param(value = "id", required = true) String id, Request request) {
        if (isIdIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(value = "/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntry(@Param(value = "id", required = true) String id) {
        if (isIdIncorrect(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        MemorySegment key = MemorySegment.ofArray(id.toCharArray());
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        Response response;
        if (METHODS.contains(request.getMethodName())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
            sendResponse(session, response);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            sendResponse(session, response);
        }
        sendResponse(session, response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session)  {
        Duration timeout = Duration.of(1, ChronoUnit.SECONDS);
        LocalDateTime deadlineRequest = LocalDateTime.now(ServerZone).plus(timeout);
        try {
            executor.execute(() -> process(request, session, deadlineRequest));
        } catch (RejectedExecutionException e) {
            log.error(e.toString());
            sendResponse(session,new Response("429 Too Many Requests", Response.EMPTY));
        }
    }


    private void process(Request request, HttpSession session, LocalDateTime deadlineRequest) {
        LocalDateTime now = LocalDateTime.now(ServerZone);
        if (now.isAfter(deadlineRequest)) {
            sendResponse(session, new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        try {
            super.handleRequest(request, session);
        } catch (Exception e) {
            log.error(e.toString());
            if(e instanceof HttpException) {
                sendResponse(session, new Response(Response.BAD_REQUEST, Response.EMPTY));
            } else {
                sendResponse(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            }
        }

    }

    private boolean isIdIncorrect(String id) {
        return id == null || id.isEmpty() || id.isBlank();
    }

    private void sendResponse(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            session.handleException(e);
        }
    }
}
