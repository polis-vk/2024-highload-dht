package ru.vk.itmo.test.timofeevkirill;

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
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.timofeevkirill.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static ru.vk.itmo.test.timofeevkirill.Settings.VERSION_PREFIX;

public class TimofeevServer extends HttpServer {

    private static final String PATH = VERSION_PREFIX + "/entity";
    private final ReferenceDao dao;

    public TimofeevServer(ServiceConfig serviceConfig, ReferenceDao dao) throws IOException {
        super(createServerConfig(serviceConfig));
        this.dao = dao;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        return serverConfig;
    }

    @Path(VERSION_PREFIX + "/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) {
        if (isEmptyParam(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        Entry<MemorySegment> entry = dao.get(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)));

        return entry == null
                ? new Response(Response.NOT_FOUND, Response.EMPTY)
                : Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id, Request request) {
        if (isEmptyParam(id) || isEmptyRequest(request)) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        MemorySegment value = MemorySegment.ofArray(request.getBody());
        dao.upsert(new BaseEntry<>(key, value));

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        if (isEmptyParam(id)) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        dao.upsert(new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)), null));

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = Settings.SUPPORTED_METHODS.contains(request.getMethod())
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public synchronized void stop() {
        super.stop();
    }

    private boolean isEmptyParam(String param) {
        return param == null || param.isEmpty();
    }

    private boolean isEmptyRequest(Request request) {
        return request.getBody() == null;
    }
}
