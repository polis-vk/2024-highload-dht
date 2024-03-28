package ru.vk.itmo.test.tyapuevdmitrij;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.tyapuevdmitrij.dao.BaseEntry;
import ru.vk.itmo.test.tyapuevdmitrij.dao.Entry;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static one.nio.util.Hash.murmur3;

public class ServerImplementation extends HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(ServerImplementation.class);

    private static final String ENTITY_PATH = "/v0/entity";

    private static final String REQUEST_KEY = "id=";

    private static final String FROM_PARAMETER = "from=";

    private static final String ACK_PARAMETER = "ack=";

    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";

    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private static final int POOL_KEEP_ALIVE_SECONDS = 10;

    private static final int THREAD_POOL_QUEUE_SIZE = 64;

    private final MemorySegmentDao memorySegmentDao;

    private final ExecutorService executor;

    private final ServiceConfig config;
    private final Client client;

    public ServerImplementation(ServiceConfig config, MemorySegmentDao memorySegmentDao) throws IOException {
        super(createServerConfig(config));
        this.config = config;
        this.client = new Client();
        this.memorySegmentDao = memorySegmentDao;
        this.executor = new ThreadPoolExecutor(THREAD_POOL_SIZE,
                THREAD_POOL_SIZE,
                POOL_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(THREAD_POOL_QUEUE_SIZE),
                new CustomThreadFactory("worker", true),
                new ThreadPoolExecutor.AbortPolicy());
        ((ThreadPoolExecutor) executor).prestartAllCoreThreads();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executor.close();
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executor.execute(() -> {
                try {
                    if (!request.getPath().equals(ENTITY_PATH)) {
                        handleDefault(request, session);
                        return;
                    }
                    String id = request.getParameter(REQUEST_KEY);
                    if (id == null || id.isEmpty()) {
                        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        return;
                    }
                    String proxyHeader = request.getHeader(Client.PROXY_TIMESTAMP_HEADER + ":");
                    if (proxyHeader != null) {
                        long timeStamp = Long.parseLong(proxyHeader);
                        Response response = handleNodeRequest(request, id, timeStamp);
                        session.sendResponse(response);
                        return;
                    }
                    int from = getValueFromRequest(request, FROM_PARAMETER, config.clusterUrls().size());
                    int ack = getValueFromRequest(request, ACK_PARAMETER, (config.clusterUrls().size()
                            / 2) + 1);
                    if (ack == 0 || ack > from || from > config.clusterUrls().size()) {
                        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        return;
                    }
                    long timeNow = System.currentTimeMillis();
                    List<String> url = getTargetNodesUrls(id, from);
                    List<Response> responses = collectResponses(request, from, url, id, timeNow);
                    Response finalResponse = aggregateResponses(responses, ack);
                    session.sendResponse(finalResponse);
                } catch (Exception e) {
                    logger.error("Exception in request method", e);
                    sendErrorResponse(session, Response.INTERNAL_ERROR);
                }
            });
        } catch (RejectedExecutionException exception) {
            logger.error("ThreadPool queue overflow", exception);
            sendErrorResponse(session, Response.SERVICE_UNAVAILABLE);
        }

    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    private static void sendErrorResponse(HttpSession session, String error) {
        try {
            session.sendResponse(new Response(error, Response.EMPTY));
        } catch (IOException ex) {
            logger.error("can't send response", ex);
            session.close();
        }
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

    private Response get(String id) {
        MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
        Entry<MemorySegment> entry = memorySegmentDao.get(key);
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        Response response;
        if (entry.value() == null) {
            response = new Response(Response.NOT_FOUND, Response.EMPTY);
        } else {
            response = new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
        }
        response.addHeader(Client.NODE_TIMESTAMP_HEADER + ":" + entry.timeStamp());
        return response;
    }

    private Response put(String id, Request request, long timeNow) {
        byte[] requestBody = request.getBody();
        if (requestBody == null) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(requestBody), timeNow);
        memorySegmentDao.upsert(entry);
        return new Response(Response.CREATED, Response.EMPTY);
    }

    private Response delete(String id, long timeNow) {
        Entry<MemorySegment> entry = new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                null,
                timeNow);
        memorySegmentDao.upsert(entry);
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    private Response getUnsupportedMethodResponse() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    private Response handleNodeRequest(Request request, String id, long timeNow) {
        long timeStamp = timeNow;
        String header = request.getHeader(Client.PROXY_TIMESTAMP_HEADER + ":");
        if (header != null) {
            timeStamp = Long.parseLong(header);
        }
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                return get(id);
            }
            case Request.METHOD_PUT -> {
                return put(id, request, timeStamp);
            }
            case Request.METHOD_DELETE -> {
                return delete(id, timeStamp);
            }
            default -> {
                return getUnsupportedMethodResponse();
            }
        }
    }

    private List<String> getTargetNodesUrls(String key, int size) {
        Map<Integer, String> treeMap = new TreeMap<>(Collections.reverseOrder());
        for (int i = 0; i < config.clusterUrls().size(); i++) {
            treeMap.put(getCustomHashCode(key, i), config.clusterUrls().get(i));
        }
        return new ArrayList<>(treeMap.values()).subList(0, size);
    }

    private int getCustomHashCode(String key, int nodeNumber) {
        return (murmur3(key + nodeNumber) % 101);
    }

    private int getValueFromRequest(Request request, String parameter, int defaultValue) {
        String value = request.getParameter(parameter);
        if (value == null) {
            return defaultValue;
        } else return Integer.parseInt(value);
    }

    private List<Response> collectResponses(Request request, int from, List<String> url, String id, long timeNow) {
        List<Response> responses = new ArrayList<>(from);
        for (int i = 0; i < from; i++) {
            if (url.get(i).equals(config.selfUrl())) {
                responses.add(handleNodeRequest(request, id, timeNow));
            } else {
                client.setUrl(url.get(i));
                client.setTimeStamp(timeNow);
                responses.add(client.handleProxyRequest(request));
            }
        }
        return responses;
    }

    private Response aggregateResponses(List<Response> responses, int ack) {
        int successResponses = 0;
        Response finalResponse = null;
        long finalTime = 0;
        for (Response response : responses) {
            if (response == null) {
                continue;
            }
            String dataHeader = response.getHeader(Client.NODE_TIMESTAMP_HEADER + ":");
            if (dataHeader != null) {
                long headerTime = Long.parseLong(dataHeader);
                if (headerTime >= finalTime) {
                    finalTime = headerTime;
                    finalResponse = response;
                }
            } else {
                if (finalResponse == null) {
                    finalResponse = response;
                }
            }
            successResponses++;
        }
        if (successResponses >= ack) {
            return finalResponse;
        } else {
            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
    }
}


