package ru.vk.itmo.test.bandurinvladislav;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.bandurinvladislav.dao.ReferenceDao;
import ru.vk.itmo.test.bandurinvladislav.util.Constants;
import ru.vk.itmo.test.bandurinvladislav.util.MemSegUtil;
import ru.vk.itmo.test.bandurinvladislav.util.StringUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class Server extends HttpServer {
    private final ReferenceDao dao;

    public Server(HttpServerConfig serverConfig, java.nio.file.Path workingDir) throws IOException {
        super(serverConfig);
        Config daoConfig = new Config(workingDir, Constants.FLUSH_THRESHOLD_BYTES);
        dao = new ReferenceDao(daoConfig);
    }

    public Response getEntity(@Param(value = "id", required = true) String id) {
        Entry<MemorySegment> result = dao.get(MemSegUtil.fromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
        if (request.getBody() == null || request.getBody().length == 0) {
            return new Response(Response.BAD_REQUEST, "Request body can't be empty".getBytes(StandardCharsets.UTF_8));
        }
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
        if (!path.equals(Constants.ENDPOINT)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        String key = request.getParameter(Constants.PARAMETER_ID);
        if (StringUtil.isEmpty(key)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        Response response = switch (request.getMethod()) {
            case Request.METHOD_GET -> getEntity(key);
            case Request.METHOD_PUT -> putEntity(key, request);
            case Request.METHOD_DELETE -> deleteEntity(key);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };

        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
