package ru.vk.itmo.test.khadyrovalmasgali.server;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.khadyrovalmasgali.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public class DaoServer extends HttpServer {

    private final ReferenceDao dao;
    private static final int FLUSH_THRESHOLD_BYTES = 1024 * 1024;

    public DaoServer(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        this.dao = new ReferenceDao(
                new Config(
                        config.workingDir(),
                        FLUSH_THRESHOLD_BYTES));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) {

        Entry<MemorySegment> entry = dao.get(stringToMemorySegment(id));
        if (entry == null || entry.value() == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        return Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsert(@Param(value = "id", required = true) String id, byte[] data) {
        dao.upsert(new BaseEntry<>(stringToMemorySegment(id), MemorySegment.ofArray(data)));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        dao.upsert(new BaseEntry<>(stringToMemorySegment(id), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private static MemorySegment stringToMemorySegment(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;
        serverConfig.closeSessions = true;
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        return serverConfig;
    }
}
