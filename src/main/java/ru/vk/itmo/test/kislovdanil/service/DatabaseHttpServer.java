package ru.vk.itmo.test.kislovdanil.service;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DatabaseHttpServer extends HttpServer {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String ENTITY_ACCESS_URL = "/v0/entity";
    private final ThreadPoolExecutor queryExecutor = new ThreadPoolExecutor(4, 12,
            10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100000));

    public DatabaseHttpServer(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(transformConfig(config));
        this.dao = dao;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    private void handleEntityRequestTask(int method, String entityKey, byte[] body,
                                         HttpSession session) {
        Response response;
        if (entityKey.isEmpty()) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            MemorySegment key = fromString(entityKey);
            response = switch (method) {
                case Request.METHOD_GET -> getEntity(key);
                case Request.METHOD_PUT -> putEntity(key, body);
                case Request.METHOD_DELETE -> deleteEntity(key);
                default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            };
        }
        for (int i = 0; i < 3;) {
            try {
                session.sendResponse(response);
                return;
            } catch (IOException e) {
                i++;
            }
        }
    }

    @Path(ENTITY_ACCESS_URL)
    public void handleEntityRequest(Request request, HttpSession session,
                                        @Param(value = "id", required = true) String entityKey) {
        try {
            queryExecutor.execute(() -> handleEntityRequestTask(request.getMethod(),
                    entityKey, request.getBody(), session));
        } catch (RejectedExecutionException e) {
            try {
                session.sendError(Response.SERVICE_UNAVAILABLE,
                        "Service temporary unavailable, retry later");
            } catch (IOException _) {
                throw new NetworkException();
            }
        }
    }

    private Response putEntity(MemorySegment entityKey, byte[] entityValue) {
        dao.upsert(new BaseEntry<>(entityKey, MemorySegment.ofArray(entityValue)));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response getEntity(MemorySegment entityKey) {
        Entry<MemorySegment> data = dao.get(entityKey);
        if (data == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        } else {
            return Response.ok(data.value().toArray(ValueLayout.OfByte.JAVA_BYTE));
        }
    }

    private Response deleteEntity(MemorySegment entityKey) {
        dao.upsert(new BaseEntry<>(entityKey, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static HttpServerConfig transformConfig(ServiceConfig serviceConfig) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    private static MemorySegment fromString(String data) {
        return (data == null) ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
