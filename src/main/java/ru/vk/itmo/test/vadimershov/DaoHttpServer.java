package ru.vk.itmo.test.vadimershov;

import one.nio.http.Header;
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
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.FailedSharding;
import ru.vk.itmo.test.vadimershov.exceptions.NotFoundException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

public class DaoHttpServer extends HttpServer {

    private static final long DURATION = 1000;
    private static final Set<Integer> SUPPORTED_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final ShardingDao dao;
    private final RequestThreadExecutor executor;

    public DaoHttpServer(
            ServiceConfig config,
            Config daoConfig
    ) throws IOException {
        this(config, daoConfig, new RequestThreadExecutor.Config());

    }

    public DaoHttpServer(
            ServiceConfig config,
            Config daoConfig,
            RequestThreadExecutor.Config executorConfig
    ) throws IOException {
        super(getHttpServerConfig(config));
        this.dao = new ShardingDao(config, daoConfig);
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
                ? DaoResponse.empty(DaoResponse.BAD_REQUEST)
                : DaoResponse.empty(DaoResponse.METHOD_NOT_ALLOWED);
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
                        return;
                    }
                    if (request.getMethod() == Request.METHOD_GET
                        && request.getParameter("start=") != null) {
                        rangeMapping(request.getParameter("start="), request.getParameter("end="), session);
                        return;
                    }
                    super.handleRequest(request, session);
                } catch (DaoException e) {
                    logger.error("Exception with local dao: {}", e.getMessage(), e);
                    sessionSendResponse(session, DaoResponse.INTERNAL_ERROR);
                } catch (RemoteServiceException e) {
                    logger.error("Exception in remote service: {}", e.getUrl(), e);
                    sessionSendResponse(session, e.getHttpCode());
                } catch (FailedSharding e) {
                    logger.error("Exception sharding service: {}", e.getMessage(), e);
                    sessionSendResponse(session, e.getHttpCode());
                } catch (Exception e) {
                    logger.error("Exception from one nio handle", e);
                    sessionSendResponse(session, DaoResponse.BAD_REQUEST);
                }
            });
        } catch (RejectedExecutionException e) {
            logger.error(e.getMessage());
            sessionSendResponse(session, DaoResponse.NOT_ENOUGH_REPLICAS);
        }
    }

    private void sessionSendResponse(HttpSession session, String serviceUnavailable) {
        try {
            session.sendResponse(DaoResponse.empty(serviceUnavailable));
        } catch (IOException e) {
            logger.error("Exception with send bad response", e);
        }
    }

    @Override
    public synchronized void stop() {
        this.executor.shutdown();
        this.dao.close();
        super.stop();
    }

    public void rangeMapping(
            @Nonnull String start,
            String end,
            HttpSession session
    ) throws IOException {
        if (start.isBlank() || (end != null && end.isBlank())){
            session.sendError(DaoResponse.BAD_REQUEST, null);
            return;
        }
        Iterator<TimestampEntry<MemorySegment>> range = dao.range(start, end);

        ChunkResponse chunkResponse = new ChunkResponse(session);

        while (range.hasNext()) {
            TimestampEntry<MemorySegment> current = range.next();
            chunkResponse.send(current);
        }
        chunkResponse.end();
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getMapping(
            @Param(value = "id", required = true) String id,
            @Param(value = "ack") Integer ack,
            @Param(value = "from") Integer from,
            @Header(value = "X-inner") boolean inner
    ) throws DaoException, RemoteServiceException, NotFoundException {
        if (id.isBlank()) {
            return DaoResponse.empty(DaoResponse.BAD_REQUEST);
        }

        ResultResponse response;
            if (inner) {
                response = dao.get(id);
            } else {
                response = dao.get(id, ack, from);
            }

        if (response.httpCode() == 404) {
            return DaoResponse.empty(DaoResponse.NOT_FOUND, response.timestamp());
        }
        return DaoResponse.ok(response.value(), response.timestamp());
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsertMapping(
            @Param(value = "id", required = true) String id,
            @Param(value = "ack") Integer ack,
            @Param(value = "from") Integer from,
            @Header(value = "X-inner") boolean inner,
            @Header(value = "X-timestamp") Long timestamp,
            Request request
    ) throws DaoException, RemoteServiceException {
        if (id.isBlank() || request.getBody() == null) {
            return DaoResponse.empty(DaoResponse.BAD_REQUEST);
        }

        if (inner) {
            dao.upsert(id, request.getBody(), timestamp);
        } else {
            dao.upsert(id, request.getBody(), ack, from);
        }

        return DaoResponse.empty(DaoResponse.CREATED);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteMapping(
            @Param(value = "id", required = true) String id,
            @Param(value = "ack") Integer ack,
            @Param(value = "from") Integer from,
            @Header(value = "X-inner") boolean inner,
            @Header(value = "X-timestamp") Long timestamp
    ) throws DaoException, RemoteServiceException {
        if (id.isBlank()) {
            return DaoResponse.empty(DaoResponse.BAD_REQUEST);
        }

        if (inner) {
            dao.delete(id, timestamp);
        } else {
            dao.delete(id, ack, from);
        }
        return DaoResponse.empty(DaoResponse.ACCEPTED);
    }

}
