package ru.vk.itmo.test.vadimershov;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toByteArray;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toDeletedEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toMemorySegment;

public class DaoHttpServer extends HttpServer {

    private static final long DURATION = 1000;
    private static final Set<Integer> SUPPORTED_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );

    private final Logger logger = LoggerFactory.getLogger(DaoHttpServer.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final RequestThreadExecutor executor;

    public DaoHttpServer(
            ServiceConfig config,
            ReferenceDao dao
    ) throws IOException {
        super(getHttpServerConfig(config));
        this.dao = dao;
        this.executor = new RequestThreadExecutor(new RequestThreadExecutor.Config());
    }

    public DaoHttpServer(
            ServiceConfig config,
            ReferenceDao dao,
            RequestThreadExecutor.Config executorConfig
    ) throws IOException {
        super(getHttpServerConfig(config));
        this.dao = dao;
        this.executor = new RequestThreadExecutor(executorConfig);
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
        DaoResponse daoResponse = SUPPORTED_METHODS.contains(request.getMethod())
                ? new DaoResponse(DaoResponse.BAD_REQUEST, DaoResponse.EMPTY)
                : new DaoResponse(DaoResponse.METHOD_NOT_ALLOWED, DaoResponse.EMPTY);
        session.sendResponse(daoResponse);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        try {
            long expiration = System.currentTimeMillis() + DURATION;
            executor.execute(() -> {
                try {
                    if (System.currentTimeMillis() > expiration) {
                        sessionSendResponse(session, DaoResponse.SERVICE_UNAVAILABLE);
                    } else {
                        super.handleRequest(request, session);
                    }
                } catch (DaoException e) {
                    logger.error(e.getMessage());
                    sessionSendResponse(session, DaoResponse.INTERNAL_ERROR);
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    sessionSendResponse(session, DaoResponse.BAD_REQUEST);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error(e.getMessage());
            sessionSendResponse(session, DaoResponse.TOO_MANY_REQUESTS);
        }
    }

    private void sessionSendResponse(HttpSession session, String serviceUnavailable) {
        try {
            session.sendResponse(DaoResponse.empty(serviceUnavailable));
        } catch (IOException ex) {
            logger.error(ex.getMessage());
        }
    }

    @Override
    public synchronized void stop() {
        this.executor.shutdown();
        super.stop();
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getMapping(
            @Param(value = "id", required = true) String id
    ) {
        return handleDaoException(() -> {
            if (id.isBlank()) {
                return DaoResponse.empty(DaoResponse.BAD_REQUEST);
            }

            Entry<MemorySegment> entry = dao.get(toMemorySegment(id));
            if (entry == null) {
                return DaoResponse.empty(DaoResponse.NOT_FOUND);
            }

            byte[] value = toByteArray(entry.value());
            return DaoResponse.ok(value);
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
                return DaoResponse.empty(DaoResponse.BAD_REQUEST);
            }

            dao.upsert(toEntity(id, request.getBody()));
            return DaoResponse.empty(DaoResponse.CREATED);
        });
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteMapping(
            @Param(value = "id", required = true) String id
    ) {
        return handleDaoException(() -> {
            if (id.isBlank()) {
                return DaoResponse.empty(DaoResponse.BAD_REQUEST);
            }
            dao.upsert(toDeletedEntity(id));
            return DaoResponse.empty(DaoResponse.ACCEPTED);
        });
    }

    private DaoResponse handleDaoException(Supplier<DaoResponse> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new DaoException(e);
        }
    }

}
