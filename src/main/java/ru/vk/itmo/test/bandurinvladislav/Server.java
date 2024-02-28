package ru.vk.itmo.test.bandurinvladislav;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.bandurinvladislav.dao.ReferenceDao;
import ru.vk.itmo.test.bandurinvladislav.util.MemSegUtil;
import ru.vk.itmo.test.bandurinvladislav.util.StringUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Server extends HttpServer {
    private static final String ENDPOINT = "/v0/entity";
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private final DaoWorkerPool workerPool;
    private final ReferenceDao dao;

    public Server(HttpServerConfig serverConfig, java.nio.file.Path workingDir) throws IOException {
        super(serverConfig);
        Config daoConfig = new Config(workingDir, 42 * 1024 * 1024);
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        workerPool = new DaoWorkerPool(
                availableProcessors,
                availableProcessors * 2,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(30_000)
        );

        dao = new ReferenceDao(daoConfig);
        logger.info("Server started");
    }

    public Response getEntity(@Param(value = "id", required = true) String id) {
        Entry<MemorySegment> result = dao.get(MemSegUtil.fromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        MemorySegment.ofArray(request.getBody())
                )
        );
        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response deleteEntity(@Param(value = "id", required = true) String id) {
        dao.upsert(
                new BaseEntry<>(
                        MemSegUtil.fromString(id),
                        null
                )
        );
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        // пока не очень понятно, насколько масштабируемое решение относительно эндпоинтов и параметров
        // мы хотим, поэтому захардкожу для одного существующего, чтобы не создавать лишних объектов
        String path = request.getPath();
        if (!path.equals(ENDPOINT)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        String key = request.getParameter("id=");
        if (StringUtil.isEmpty(key)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        workerPool.execute(() -> {
            try {
                handleDaoCall(request, session, key);
            } catch (IOException e) {
                logger.error(STR."IO exception during request handling: \{e.getMessage()}");
            }
        });
    }

    private void handleDaoCall(Request request, HttpSession session, String key) throws IOException {
        Response response;
        try {
            response = switch (request.getMethod()) {
                case Request.METHOD_GET -> getEntity(key);
                case Request.METHOD_PUT -> putEntity(key, request);
                case Request.METHOD_DELETE -> deleteEntity(key);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        } catch (Exception e) {
            logger.error(STR."Internal error during request handling: \{e.getMessage()}");
            response = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }

        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        super.stop();

        workerPool.gracefulShutdown();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}