package ru.vk.itmo.test.abramovilya;

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
import ru.vk.itmo.test.abramovilya.dao.DaoFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Server extends HttpServer {
    public static final String ENTITY_PATH = "/v0/entity";
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService = new ThreadPoolExecutor(
            4,
            8,
            1,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(80)
    );

    public Server(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createConfig(config));
        this.dao = dao;
    }

    private static HttpServerConfig createConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        try {
            Response response = new Response(Response.BAD_REQUEST, "Unknown path".getBytes(StandardCharsets.UTF_8));
            session.sendResponse(response);
        } catch (Exception e) {
            session.sendError(Response.INTERNAL_ERROR, "");
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (IOException e) {
                    logger.info("IOException for request: " + request);
                    throw new UncheckedIOException(e);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.info("Execution rejected for request: " + request);
            session.sendError(Response.SERVICE_UNAVAILABLE, "");
        }
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executorService.close();
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        
        Entry<MemorySegment> entry = dao.get(DaoFactory.fromString(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return responseOk(entry.value());
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id") String id, Request request) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), MemorySegment.ofArray(request.getBody())));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_POST)
    public Response postEntry() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    @Path(ENTITY_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id") String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        dao.upsert(new BaseEntry<>(DaoFactory.fromString(id), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static Response responseOk(MemorySegment memorySegment) {
        return new Response(Response.OK, memorySegment.toArray(ValueLayout.JAVA_BYTE));
    }
}
