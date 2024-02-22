package ru.vk.itmo.test.ryabovvadim.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ryabovvadim.dao.InMemoryDao;
import ru.vk.itmo.test.ryabovvadim.utils.HttpServerConstants;
import ru.vk.itmo.test.ryabovvadim.utils.MemorySegmentUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public class SimpleServer extends HttpServer {

    private static final long DAO_FLUSH_THRESHOLD_BYTES = 4096;
    private static final String V0_BASE_PATH = "/v0/entity";
    private static final Set<Integer> IMPLEMENTED_METHODS = new HashSet<>();

    static {
        Method[] classMethods = SimpleServer.class.getDeclaredMethods();

        for (Method classMethod : classMethods) {
            RequestMethod requestMethodAnnotation = classMethod.getAnnotation(RequestMethod.class);
            if (requestMethodAnnotation == null) {
                continue;
            }

            for (int requestMethod : requestMethodAnnotation.value()) {
                IMPLEMENTED_METHODS.add(requestMethod);
            }
        }
    }

    private final Config daoConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    public SimpleServer(ServiceConfig serviceConfig) throws IOException {
        super(createServerConfig(serviceConfig));
        daoConfig = new Config(serviceConfig.workingDir(), DAO_FLUSH_THRESHOLD_BYTES);
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();

        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public synchronized void start() {
        try {
            dao = new InMemoryDao(daoConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        super.start();
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

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        if (IMPLEMENTED_METHODS.contains(request.getMethod())) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        }
        session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
    }

    @Path(V0_BASE_PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response getValueById(@Param(value = "id", required = true) String id) {
        if (id.isBlank()) {
            return HttpServerConstants.BAD_REQUEST;
        }

        MemorySegment key = MemorySegmentUtils.fromString(id);
        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return HttpServerConstants.NOT_FOUND;
        }

        MemorySegment value = entry.value();
        return Response.ok(value.toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(V0_BASE_PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntry(
        @Param(value = "id", required = true) String id,
        Request request
    ) {
        if (id.isBlank()) {
            return HttpServerConstants.BAD_REQUEST;
        }

        MemorySegment key = MemorySegmentUtils.fromString(id);
        MemorySegment value = MemorySegmentUtils.fromBytes(request.getBody());

        try {
            dao.upsert(new BaseEntry<>(key, value));
            return HttpServerConstants.CREATED;
        } catch (Exception ex) {
            return HttpServerConstants.BAD_REQUEST;
        }
    }

    @Path(V0_BASE_PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntry(@Param(value = "id", required = true) String id) {
        if (id.isBlank()) {
            return HttpServerConstants.BAD_REQUEST;
        }

        MemorySegment key = MemorySegmentUtils.fromString(id);
        try {
            dao.upsert(new BaseEntry<>(key, null));
            return HttpServerConstants.ACCEPTED;
        } catch (Exception ex) {
            return HttpServerConstants.BAD_REQUEST;
        }
    }
}
