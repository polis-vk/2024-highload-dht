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
import ru.vk.itmo.test.reference.dao.ReferenceDao;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;

import java.io.IOException;
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
            ReferenceDao localDao
    ) throws IOException {
        this(config, localDao, new RequestThreadExecutor.Config());
    }

    public DaoHttpServer(
            ServiceConfig config,
            ReferenceDao localDao,
            RequestThreadExecutor.Config executorConfig
    ) throws IOException {
        super(getHttpServerConfig(config));
        this.dao = new ShardingDao(config, localDao);
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
                    super.handleRequest(request, session);
                } catch (DaoException e) {
                    logger.error(e.getMessage());
                    sessionSendResponse(session, DaoResponse.INTERNAL_ERROR);
                } catch (RemoteServiceException e) {
                    logger.error("Exception in remote service: {}", e.getUrl());
                    sessionSendResponse(session, e.getHttpCode());
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
        if (id.isBlank()) {
            return DaoResponse.empty(DaoResponse.BAD_REQUEST);
        }
        byte[] value = dao.get(id);
        if (value.length == 0) {
            return DaoResponse.empty(DaoResponse.NOT_FOUND);
        }
        return DaoResponse.ok(value);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsertMapping(
            @Param(value = "id", required = true) String id,
            Request request
    ) {
        if (id.isBlank() || request.getBody() == null) {
            return DaoResponse.empty(DaoResponse.BAD_REQUEST);
        }

        dao.upsert(id, request.getBody());
        return DaoResponse.empty(DaoResponse.CREATED);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteMapping(
            @Param(value = "id", required = true) String id
    ) {
        if (id.isBlank()) {
            return DaoResponse.empty(DaoResponse.BAD_REQUEST);
        }
        dao.delete(id);
        return DaoResponse.empty(DaoResponse.ACCEPTED);
    }

}
