package ru.vk.itmo.test.vadimershov;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.function.Supplier;

import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toByteArray;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toDeletedEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toMemorySegment;

public class DaoHttpServer extends HttpServer {

    private static final Set<Integer> SUPPORTED_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );

    private final Logger logger = LoggerFactory.getLogger(DaoHttpServer.class);

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public DaoHttpServer(ServiceConfig config, ReferenceDao dao) throws IOException {
        super(getHttpServerConfig(config));
        this.dao = dao;
    }

    private static HttpServerConfig getHttpServerConfig(ServiceConfig config) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = isSupportedMethod(request.getMethod())
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        session.sendResponse(response);
    }

    private boolean isSupportedMethod(int httpMethod) {
        return SUPPORTED_METHODS.contains(httpMethod);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            super.handleRequest(request, session);
        } catch (DaoException e) {
            logger.error(e.getMessage());
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (Exception e) {
            logger.error(e.getMessage());
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getMapping(
            @Param(value = "id", required = true) String id
    ) {
        return handleDaoException(() -> {
            if (id.isBlank()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }

            Entry<MemorySegment> entry = dao.get(toMemorySegment(id));
            if (entry == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }

            byte[] value = toByteArray(entry.value());
            return Response.ok(value);
        });
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsertMapping(
            @Param(value = "id", required = true) String id,
            Request request
    ) {
        return handleDaoException(() -> {
            if (id.isBlank() || request.getBody() == null) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }

            dao.upsert(toEntity(id, request.getBody()));
            return new Response(Response.CREATED, Response.EMPTY);
        });
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteMapping(
            @Param(value = "id", required = true) String id
    ) {
        return handleDaoException(() -> {
            if (id.isBlank()) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            dao.upsert(toDeletedEntity(id));
            return new Response(Response.ACCEPTED, Response.EMPTY);
        });
    }

    private Response handleDaoException(Supplier<Response> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new DaoException(e);
        }
    }

}
