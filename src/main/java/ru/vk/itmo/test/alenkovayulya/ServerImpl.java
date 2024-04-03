package ru.vk.itmo.test.alenkovayulya;

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
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

import static ru.vk.itmo.test.alenkovayulya.ShardRouter.redirectRequest;

public class ServerImpl extends HttpServer {
    public static final String PATH = "/v0/entity";
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerImpl.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> referenceDao;
    private final ExecutorService executorService;
    private final String url;
    private final ShardSelector shardSelector;

    public ServerImpl(ServiceConfig serviceConfig,
                      Dao<MemorySegment, Entry<MemorySegment>> referenceDao,
                      ExecutorService executorService, ShardSelector shardSelector) throws IOException {
        super(createServerConfig(serviceConfig));
        this.referenceDao = referenceDao;
        this.executorService = executorService;
        this.url = serviceConfig.selfUrl();
        this.shardSelector = shardSelector;
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
    public void handleRequest(Request request, HttpSession session) {
        executorService.execute(() -> {
            try {
                super.handleRequest(request, session);
            } catch (Exception e) {
                try {
                    session.sendError(Response.BAD_REQUEST, e.getMessage());
                } catch (IOException ex) {
                    LOGGER.info("Exception during sending the response: ", ex);
                    session.close();
                }
            }
        });
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_GET)
    public Response getEntity(@Param(value = "id", required = true) String id) {
            if (isEmptyId(id)) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            String ownerShardUrl = shardSelector.getOwnerShardUrl(id);
            if (isRedirectNeeded(ownerShardUrl)) {
                return redirectRequest("GET", id, ownerShardUrl, new byte[0]);
            }
            Entry<MemorySegment> value = referenceDao.get(
                    convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)));

            return value == null ? new Response(Response.NOT_FOUND, Response.EMPTY)
                    : Response.ok(value.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_PUT)
    public Response putEntity(@Param(value = "id", required = true) String id, Request request) {
            if (isEmptyId(id)) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            String ownerShardUrl = shardSelector.getOwnerShardUrl(id);
            if (isRedirectNeeded(ownerShardUrl)) {
                return redirectRequest("PUT", id, ownerShardUrl, request.getBody());
            }
            referenceDao.upsert(new BaseEntry<>(
                    convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                    convertBytesToMemorySegment(request.getBody())));
            return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path(PATH)
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteEntity(@Param(value = "id", required = true) String id) {
            String ownerShardUrl = shardSelector.getOwnerShardUrl(id);
            if (isRedirectNeeded(ownerShardUrl)) {
                return redirectRequest("DELETE", id, ownerShardUrl, new byte[0]);
            }
            if (isEmptyId(id)) {
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }
            referenceDao.upsert(new BaseEntry<>(
                    convertBytesToMemorySegment(id.getBytes(StandardCharsets.UTF_8)), null));
            return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        switch (request.getMethodName()) {
            case "GET", "PUT", "DELETE" -> session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            default -> session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

    private boolean isRedirectNeeded(String ownerUrl) {
        return !url.equals(ownerUrl);
    }

    private boolean isEmptyId(String id) {
        return id.isEmpty() && id.isBlank();
    }

    private MemorySegment convertBytesToMemorySegment(byte[] byteArray) {
        return MemorySegment.ofArray(byteArray);
    }

}
