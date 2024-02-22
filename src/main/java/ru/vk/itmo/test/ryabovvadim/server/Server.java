package ru.vk.itmo.test.ryabovvadim.server;

import one.nio.http.*;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ryabovvadim.dao.InMemoryDao;
import ru.vk.itmo.test.ryabovvadim.utils.MemorySegmentUtils;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

//MY NOTE: think about name
public class Server extends HttpServer {

    private static final long DAO_FLUSH_THRESHOLD_BYTES = 4096;

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public Server(ServiceConfig serviceConfig) throws IOException {
        super(createServerConfig(serviceConfig));

        Config daoConfig = new Config(serviceConfig.workingDir(), DAO_FLUSH_THRESHOLD_BYTES);
        this.dao = new InMemoryDao(daoConfig);
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();

        acceptorConfig.address = serviceConfig.selfUrl();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getValueById(@Param(value = "id", required = true) String id) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST);
        }

        MemorySegment key = MemorySegmentUtils.fromString(id);
        Entry<MemorySegment> entry = dao.get(key);

        if (entry == null) {
            return new Response(Response.NOT_FOUND);
        }

        MemorySegment value = entry.value();
        return Response.ok(value.toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntry(
        @Param(value = "id", required = true) String id,
        Request request
    ) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST);
        }

        MemorySegment key = MemorySegmentUtils.fromString(id);
        MemorySegment value = MemorySegmentUtils.fromBytes(request.getBody());

        try {
            dao.upsert(new BaseEntry<>(key, value));
            return new Response(Response.CREATED);
        } catch (Exception ex) {
            //MY NOTE: think
            return new Response(Response.BAD_REQUEST);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntry(@Param(value = "id", required = true) String id) {
        if (id.isBlank()) {
            return new Response(Response.BAD_REQUEST);
        }

        MemorySegment key = MemorySegmentUtils.fromString(id);

        try {
            dao.upsert(new BaseEntry<>(key, null));
            return new Response(Response.ACCEPTED);
        } catch (Exception ex) {
            //MY NOTE: think
            return new Response(Response.BAD_REQUEST);
        }
    }
}
