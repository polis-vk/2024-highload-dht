package ru.vk.itmo.test.pelogeikomakar;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public class DaoHttpServer extends one.nio.http.HttpServer {

    private static final Logger log = LoggerFactory.getLogger(DaoHttpServer.class);
    private final ExecutorService executorService;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final Set<Integer> ALLOWED_METHODS = Set.of(Request.METHOD_GET, Request.METHOD_PUT,
            Request.METHOD_DELETE);
    private final List<String> clusterUrls;
    private final String selfUrl;
    private final ConcurrentMap<String, HttpClient> clients;

    public DaoHttpServer(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao,
                         ExecutorService executorService) throws IOException {
        super(createHttpServerConfig(config));
        this.clusterUrls = config.clusterUrls();
        this.selfUrl = config.selfUrl();
        this.clients = new ConcurrentHashMap<>();
        for (String url : this.clusterUrls) {
            this.clients.put(url, new HttpClient(new ConnectionString(url), "INTERNAL_RQ " + selfUrl));
        }
        this.dao = dao;
        this.executorService = executorService;
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public Dao<MemorySegment, Entry<MemorySegment>> getDao() {
        return dao;
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsertDaoMethod(@Param(value = "id", required = false) String id, Request request) {

        if (id == null || id.isEmpty() || request.getBody() == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String url = getServerUrlForKey(id);
        if (url.equals(selfUrl)) {
            try {
                dao.upsert(requestToEntry(id, request.getBody()));
            } catch (IllegalStateException e) {
                log.error("Exception during upsert (key: {})", id, e);
                return new Response(Response.CONFLICT, Response.EMPTY);
            }

            return new Response(Response.CREATED, Response.EMPTY);

        } else {
            if (request.getHeader("INTERNAL_RQ") != null) {
                log.warn("Cycle redirect in PUT key: {}, from: {}, now: {}, to: {}",
                        id, request.getHeader("INTERNAL_RQ"), selfUrl, url);
            }
            String statusStr = putDataToServer(url, id, request.getBody());
            return new Response(statusStr, Response.EMPTY);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response getDaoMethod(@Param(value = "id", required = false) String id) {
        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String url = getServerUrlForKey(id);

        if (url.equals(selfUrl)) {
            Entry<MemorySegment> result = dao.get(stringToMemorySegment(id));
            if (result == null) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }
            return Response.ok(memorySegmentToBytes(result.value()));

        } else {
            Response subResponse = getDataFromServer(url, id);
            String status = subResponse.getHeaders()[0];
            byte[] body = subResponse.getBody();
            return new Response(status, body);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response deleteDaoMethod(@Param(value = "id", required = false) String id) {

        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        String url = getServerUrlForKey(id);

        if (url.equals(selfUrl)) {
            try {
                dao.upsert(requestToEntry(id, null));
            } catch (IllegalStateException e) {
                log.error("Exception during delete-upsert", e);
                return new Response(Response.CONFLICT, Response.EMPTY);
            }

            return new Response(Response.ACCEPTED, Response.EMPTY);

        } else {
            Response subResponse = deleteDataFromServer(url, id);
            String status = subResponse.getHeaders()[0];
            byte[] body = subResponse.getBody();
            return new Response(status, body);
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    try {
                        session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                    } catch (IOException ex) {
                        log.error("IOException while sendResponse ExecServer", ex);
                        session.scheduleClose();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Exception during adding request to ExecServ queue", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {

        Response response;
        if (ALLOWED_METHODS.contains(request.getMethod())) {
            response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        } else {
            response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
        session.sendResponse(response);
    }

    private MemorySegment stringToMemorySegment(String str) {
        return MemorySegment.ofArray(str.getBytes(StandardCharsets.UTF_8));
    }

    private String getServerUrlForKey(String key) {
        int highestScore = Integer.MIN_VALUE;
        String preferredUrl = selfUrl;
        for (String url : clusterUrls) {
            int currentHash = Objects.hash(url, key);
            if (highestScore < currentHash) {
                highestScore = currentHash;
                preferredUrl = url;
            }
        }
        return preferredUrl;
    }

    private Response getDataFromServer(String serverUrl, String key) {
        Response result = new Response(Response.BAD_GATEWAY, Response.EMPTY);
        HttpClient client = clients.get(serverUrl);

        try {
            synchronized (client) {
                result = clients.get(serverUrl).get(requestForKey(key));
            }
        } catch (PoolException | IOException | HttpException e) {
            log.error("Error in GET internal request", e);
        } catch (InterruptedException e) {
            log.error("Error in GET internal request", e);
            Thread.currentThread().interrupt();
        }

        return result;

    }

    private String putDataToServer(String serverUrl, String key, byte[] value) {
        HttpClient client = clients.get(serverUrl);
        String responseStatus = Response.BAD_GATEWAY;
        try {
            Response result;
            synchronized (client) {
                result = clients.get(serverUrl).put(requestForKey(key), value);
            }
            responseStatus = result.getHeaders()[0];

        } catch (PoolException | IOException | HttpException e) {
            log.error("Error in PUT internal request", e);

        } catch (InterruptedException e) {
        log.error("Error in PUT internal request", e);
        Thread.currentThread().interrupt();
    }
        return responseStatus;
    }

    private Response deleteDataFromServer(String serverUrl, String key) {
        HttpClient client = clients.get(serverUrl);
        Response result = new Response(Response.BAD_GATEWAY, Response.EMPTY);

        try {
            synchronized (client) {
                result = clients.get(serverUrl).delete(requestForKey(key));
            }
        } catch (PoolException | IOException | HttpException e) {
            log.error("Error in DELETE internal request", e);
        } catch (InterruptedException e) {
            log.error("Error in DELETE internal request", e);
            Thread.currentThread().interrupt();
        }

        return result;

    }

    private String requestForKey(String key) {
        return "/v0/entity?id=" + key;
    }

    public void stopHttpClients() {
        for (HttpClient client : clients.values()) {
            client.close();
        }
    }

    private Entry<MemorySegment> requestToEntry(String key, byte[] value) {
        return new BaseEntry<>(stringToMemorySegment(key), value == null ? null : MemorySegment.ofArray(value));
    }

    private byte[] memorySegmentToBytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }
}
