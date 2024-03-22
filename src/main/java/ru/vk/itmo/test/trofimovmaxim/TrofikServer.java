package ru.vk.itmo.test.trofimovmaxim;

import one.nio.http.HttpException;
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
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.trofimovmaxim.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.RejectedExecutionException;

public class TrofikServer extends HttpServer {
    private static final long FLUSH_THRESHOLD_BYTES = 42 * 1024 * 1024;
    private static final Response BAD_RESPONSE = new Response(Response.BAD_REQUEST, Response.EMPTY);
    private static final Response INTERNAL_ERROR = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
    private static final Response TIMEOUT = new Response(Response.REQUEST_TIMEOUT, Response.EMPTY);
    private ReferenceDao dao;
    private final ServiceConfig config;
    private final DaoOperationsExecutor executor;

    public TrofikServer(ServiceConfig config) throws IOException {
        super(convertConfig(config));
        this.config = config;
        executor = new DaoOperationsExecutor();
    }

    private static HttpServerConfig convertConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(BAD_RESPONSE);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executor.run(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    try {
                        if (e instanceof HttpException) {
                            session.sendResponse(BAD_RESPONSE);
                        } else {
                            session.sendResponse(INTERNAL_ERROR);
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendResponse(TIMEOUT);
        }
    }

    @Override
    public synchronized void start() {
        try {
            dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        executor.start();
        super.start();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executor.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Entry<MemorySegment> entry(String key, byte[] value) {
        return new BaseEntry<>(memorySegmentFromString(key), value != null ? MemorySegment.ofArray(value) : null);
    }

    private MemorySegment memorySegmentFromString(String str) {
        return MemorySegment.ofArray(str.getBytes(StandardCharsets.UTF_8));
    }

    @Path("/v0/entity")
    public Response v0Entity(Request request,
                             @Param(value = "id", required = true) String id) {
        if (id.isEmpty()) {
            return BAD_RESPONSE;
        }

        return switch (request.getMethod()) {
            case Request.METHOD_PUT -> entityPut(request, id);
            case Request.METHOD_GET -> entityGet(id);
            case Request.METHOD_DELETE -> entityDelete(id);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }

    public Response entityGet(String id) {
        Entry<MemorySegment> result = dao.get(memorySegmentFromString(id));
        if (result == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
    }

    public Response entityPut(Request request, String id) {
        if (request.getBody() == null) {
            return BAD_RESPONSE;
        }
        dao.upsert(entry(id, request.getBody()));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    public Response entityDelete(String id) {
        dao.upsert(entry(id, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }
}
