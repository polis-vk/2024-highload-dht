package ru.vk.itmo.test.smirnovandrew;

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
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

public class MyServer extends HttpServer {

    private static final String ROOT = "/v0/entity";

    private final ReferenceDao dao;

    private static final Set<Integer> METHOD_SET = new ConcurrentSkipListSet<>(List.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    ));

    private static HttpServerConfig generateServerConfig(ServiceConfig config) {
        var serverConfig = new HttpServerConfig();
        var acceptorsConfig = new AcceptorConfig();

        acceptorsConfig.port = config.selfPort();
        acceptorsConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorsConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public MyServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(generateServerConfig(config));
        this.dao = dao;
    }

    private boolean isStringInvalid(String param) {
        return Objects.isNull(param) || "".equals(param);
    }

    private MemorySegment fromString(String data) {
        if (data == null) {
            return null;
        }
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_GET)
    public Response get(
            @Param(value = "id", required = true) String id
    ) {
        if (isStringInvalid(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        var key = fromString(id);
        var got = dao.get(key);

        if (Objects.isNull(got)) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return Response.ok(got.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(
            @Param(value = "id", required = true) String id
    ) {
        if (isStringInvalid(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        var key = fromString(id);
        dao.upsert(new BaseEntry<>(key, null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Path(ROOT)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(
            @Param(value = "id", required = true) String id,
            Request request
    ) {
        if (isStringInvalid(id)) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        var key = fromString(id);
        var value = MemorySegment.ofArray(request.getBody());

        dao.upsert(new BaseEntry<>(key, value));
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(
                METHOD_SET.contains(request.getMethod())
                        ?
                        new Response(Response.BAD_REQUEST, Response.EMPTY) :
                        new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY)
                );
    }
}
