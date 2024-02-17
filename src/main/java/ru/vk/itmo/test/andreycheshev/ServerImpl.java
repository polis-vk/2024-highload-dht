package ru.vk.itmo.test.andreycheshev;

import one.nio.http.*;
import one.nio.os.Mem;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.andreycheshev.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.*;
import static one.nio.http.Response.*;

public class ServerImpl extends HttpServer {
    private final ReferenceDao dao;

    public ServerImpl(ServiceConfig config) throws IOException {
        super(createServerConfig(config));

        Config daoConfig = new Config(config.workingDir(), 100000);
        this.dao = new ReferenceDao(daoConfig);
    }


    public static HttpServerConfig createServerConfig(ServiceConfig config) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        return serverConfig;
    }

    @Path("/v0/entity")
    @RequestMethod(METHOD_GET)
    public Response get(@Param("id") String id) {
        System.out.println("get");

        MemorySegment key = fromString(id);

        Entry<MemorySegment> entry = dao.get(key);

        return (entry == null || entry.key() == null)
                ? new Response(NOT_FOUND, new byte[]{})
                : Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(METHOD_PUT)
    public Response put(@Param("id") String id, final Request request) {
        System.out.println("put");
        byte[] body = request.getBody();
        Entry<MemorySegment> entry = new BaseEntry<>(fromString(id), fromByte(body));

        dao.upsert(entry);

        return new Response(CREATED, new byte[]{});
    }

    @Path("/v0/entity")
    @RequestMethod(METHOD_DELETE)
    public Response delete(@Param("id") String id) {
        System.out.println("delete");
        Entry<MemorySegment> entry = new BaseEntry<>(fromString(id), null);

        dao.upsert(entry);

        return new Response(ACCEPTED, new byte[]{});
    }

    private MemorySegment fromString(String data) {
        if (data == null) {
            return null;
        }
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    private MemorySegment fromByte(byte[] data) {
        return MemorySegment.ofArray(data);
    }
}
