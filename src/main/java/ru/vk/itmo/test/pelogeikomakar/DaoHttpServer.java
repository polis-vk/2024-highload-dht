package ru.vk.itmo.test.pelogeikomakar;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
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

    private static final Set<String> GOOD_STATUSES = Set.of(Response.ACCEPTED, Response.CREATED,
            Response.OK, Response.NOT_FOUND);
    private static final String TIME_HEADER = "X-VALUE_TIME";
    private static final String INTERNAL_RQ_HEADER = "X-INTERNAL_RQ";
    private static final String NOT_REPLICAS_HEADER = "504 Not Enough Replicas";
    private final int defaultAck;
    private final int defaultFrom;
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
            if (!url.equals(selfUrl)) {
                this.clients.put(url, new HttpClient(new ConnectionString(url),
                        INTERNAL_RQ_HEADER + ' ' + selfUrl));
            }
        }

        defaultFrom = this.clusterUrls.size();
        defaultAck = defaultFrom / 2 + 1;

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
    public Response processEntityDaoMethod(@Param(value = "id", required = false) String id,
                                    @Param(value = "ack", required = false) String ack,
                                    @Param(value = "from", required = false) String from,
                                    Request request) {

        if (id == null || id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        Response subResponse;
        long time = System.currentTimeMillis();

        if (request.getHeader(INTERNAL_RQ_HEADER) == null) {
            // Request from outside
            int currAck = defaultAck;
            int currFrom = defaultFrom;

            if (ack != null) {
                try {
                    currAck = Integer.parseInt(ack);
                } catch (NumberFormatException e) {
                    log.warn("Can not parse number", e);
                }
            }

            if (from != null) {
                try {
                    currFrom = Integer.parseInt(from);
                } catch (NumberFormatException e) {
                    log.warn("Can not parse number", e);
                }
            }

            if (currFrom > clusterUrls.size() || currFrom < 1 || currAck > currFrom || currAck < 1) {
                log.error("BAD ack or from parameter: [ack: {}, from: {}]", currAck, currFrom);
                return new Response(Response.BAD_REQUEST, Response.EMPTY);
            }

            String[] urls = getServerUrlsForKey(id, currFrom);
            subResponse = executeAllRequests(id, request, urls, currAck, time);

        } else {
            // Redirected request
            try {
                time = Long.parseLong(request.getHeader(TIME_HEADER));
            } catch (NumberFormatException e) {
                log.warn("Can not parse number", e);
            }
            subResponse = executeMethodLocal(id, request, time);
        }

        return subResponse;
    }

    private Response executeAllRequests(String id, Request request, String[] urls, int currAck, long timeSt) {
        int succeededReq = 0;
        long oldestTime = -2;
        Response oldestResponse = new Response(Response.INTERNAL_ERROR, Response.EMPTY);

        for (String url : urls) {
            Response currResp = executeMethod(url, id, request, timeSt);
            String status = currResp.getHeaders()[0];
            if (GOOD_STATUSES.contains(status)) {
                succeededReq++;
                long curTime = -1;
                try {
                    curTime = Long.parseLong(currResp.getHeader(TIME_HEADER));
                } catch (NumberFormatException e) {
                    log.warn("Can not parse number", e);
                }

                if (oldestTime == -2 || oldestTime < curTime) {
                    oldestTime = curTime;
                    oldestResponse = currResp;
                }
            }

            if (status.equals(Response.METHOD_NOT_ALLOWED)) {
                return currResp;
            }
        }

        if (succeededReq >= currAck) {
            return oldestResponse;
        }

        return new Response(NOT_REPLICAS_HEADER, Response.EMPTY);
    }

    private Response executeMethod(String url, String id, Request request, long timeSt) {
        Response result;
        if (url.equals(selfUrl)) {
            result = executeMethodLocal(id, request, timeSt);
        } else {
            result = executeMethodRemote(url, id, request, timeSt);
        }
        return result;
    }

    private Response executeMethodLocal(String id, Request request, long timeSt) {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                Entry<MemorySegment> result = dao.get(Convertor.stringToMemorySegment(id));
                if (result == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);

                } else if (Convertor.isValNull(result.value())) {
                    Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                    long time = Convertor.getTimeStamp(result.value());
                    response.addHeader(TIME_HEADER + ' ' + time);
                    return response;

                } else {
                    long time = Convertor.getTimeStamp(result.value());
                    Response response = new Response(Response.OK,
                            Convertor.getValueNotNullAsBytes(result.value()));
                    response.addHeader(TIME_HEADER + ' ' + time);
                    return response;
                }

            case Request.METHOD_PUT:
                try {
                    dao.upsert(Convertor.requestToEntry(id, request.getBody(), timeSt));
                } catch (IllegalStateException e) {
                    log.error("Exception during upsert (key: {})", id, e);
                    return new Response(Response.CONFLICT, Response.EMPTY);
                }
                return new Response(Response.CREATED, Response.EMPTY);

            case Request.METHOD_DELETE:
                try {
                    dao.upsert(Convertor.requestToEntry(id, null, timeSt));
                } catch (IllegalStateException e) {
                    log.error("Exception during delete-upsert", e);
                    return new Response(Response.CONFLICT, Response.EMPTY);
                }
                return new Response(Response.ACCEPTED, Response.EMPTY);

            default:
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        }
    }

    private Response executeMethodRemote(String url, String id, Request request, long timeSt) {
        return switch (request.getMethod()) {
            case Request.METHOD_GET -> getDataFromServer(url, id);
            case Request.METHOD_PUT -> putDataToServer(url, id, request.getBody(), timeSt);
            case Request.METHOD_DELETE -> deleteDataFromServer(url, id, timeSt);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
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
    public synchronized void start() {
        log.info("start server on url: {}", selfUrl);
        super.start();
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

    private String[] getServerUrlsForKey(String key, int from) {
        NavigableMap<Integer, String> allCandidates = new TreeMap<>();

        for (String url : clusterUrls) {
            int currentHash = Hash.murmur3(url + key);
            allCandidates.put(currentHash, url);
        }

        String[] resultUrls = new String[from];

        for (int i = 0; i < from; i++) {
            resultUrls[i] = allCandidates.pollLastEntry().getValue();
        }

        return resultUrls;
    }

    private Response getDataFromServer(String serverUrl, String key) {
        Response result = new Response(Response.BAD_GATEWAY, Response.EMPTY);

        try {
            result = clients.get(serverUrl).get(requestForKey(key));
        } catch (PoolException | IOException | HttpException e) {
            log.error("Error in GET internal request", e);
        } catch (InterruptedException e) {
            log.error("Error in GET internal request", e);
            Thread.currentThread().interrupt();
        }

        return result;

    }

    private Response putDataToServer(String serverUrl, String key, byte[] value, long timeSt) {
        Response result = new Response(Response.BAD_GATEWAY, Response.EMPTY);
        try {
            result = clients.get(serverUrl).put(requestForKey(key), value, TIME_HEADER + ' ' + timeSt);

        } catch (PoolException | IOException | HttpException e) {
            log.error("Error in PUT internal request", e);

        } catch (InterruptedException e) {
        log.error("Error in PUT internal request", e);
        Thread.currentThread().interrupt();
        }
        return result;
    }

    private Response deleteDataFromServer(String serverUrl, String key, long timeSt) {
        Response result = new Response(Response.BAD_GATEWAY, Response.EMPTY);

        try {
            result = clients.get(serverUrl).delete(requestForKey(key), TIME_HEADER + ' ' + timeSt);
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
}
