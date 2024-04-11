package ru.vk.itmo.test.pelogeikomakar;

import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DaoHttpServer extends one.nio.http.HttpServer {

    private static final Logger log = LoggerFactory.getLogger(DaoHttpServer.class);
    private final ExecutorService localExecutorService;
    private final ExecutorService remoteExecutorService;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private static final String TIME_HEADER = "X-VALUE_TIME";
    private static final String TIME_HEADER_ONENIO = TIME_HEADER + ": ";
    private static final String INTERNAL_RQ_HEADER = "X-INTERNAL_RQ";
    private static final String NOT_REPLICAS_HEADER = "504 Not Enough Replicas";
    private final int defaultAck;
    private final int defaultFrom;
    private final List<String> clusterUrls;
    private final String selfUrl;
    private final HttpClient httpClient;


    public DaoHttpServer(ServiceConfig config, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createHttpServerConfig(config));

        this.clusterUrls = config.clusterUrls();
        this.selfUrl = config.selfUrl();
        this.httpClient = HttpClient.newBuilder()
                .executor(ExecutorServiceFactory.newExecutorService("javaClientExecutor-"))
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        defaultFrom = this.clusterUrls.size();
        defaultAck = defaultFrom / 2 + 1;

        this.dao = dao;

        localExecutorService = ExecutorServiceFactory.newExecutorService("localExecutor-");
        remoteExecutorService = ExecutorServiceFactory.newExecutorService("remoteExecutor-");
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


    private List<CompletableFuture<Response>> invokeAsyncAllRequests(String id, Request request, String[] urls,
                                                                     long time) {

        List<CompletableFuture<Response>> futureResponses = new ArrayList<>(urls.length);

        for (String url : urls) {
            CompletableFuture<Response> futureResponse;
            if (url.equals(selfUrl)) {
                futureResponse = CompletableFuture.supplyAsync(
                        () -> executeLocalMethod(id, request, time),
                        localExecutorService);
            } else {
                futureResponse = CompletableFuture.supplyAsync(
                        () -> executeRemoteMethod(url, request, time),
                        remoteExecutorService);
            }
            futureResponses.add(futureResponse);
        }
        return futureResponses;
    }

    private void accumulateAndSendResults(List<CompletableFuture<Response>> responses, HttpSession session,
                                          int currAck, int currFrom) {

        AtomicInteger doneResponses = new AtomicInteger(0);
        AtomicInteger successResponses = new AtomicInteger(0);
        AtomicBoolean queryProcessed = new AtomicBoolean(false);
        Queue<Response> readyResponses = new ConcurrentLinkedQueue<>();

        for (CompletableFuture<Response> future : responses) {
            future.whenCompleteAsync((response, exeption) -> {
                if (exeption == null && response.getStatus() < 500) {
                    successResponses.incrementAndGet();
                    readyResponses.add(response);
                }

                doneResponses.incrementAndGet();

                if (successResponses.get() >= currAck &&
                        queryProcessed.compareAndSet(false, true)) {
                    mergeAndSend(session, readyResponses);
                    return;
                }

                if (doneResponses.get() == currFrom && successResponses.get() < currAck &&
                        queryProcessed.compareAndSet(false, true)) {
                    sendResponseWithError(session, new Response(NOT_REPLICAS_HEADER, Response.EMPTY));
                }
                }, localExecutorService)
                    .exceptionally(exception -> {
                        log.error("Error in future", exception);
                        return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
                    });
        }

    }

    private void mergeAndSend(HttpSession session, Iterable<Response> responses) {
        long youngestTime = -2;
        Response youngestResponse = responses.iterator().next();
        for (Response currentResp : responses) {
            long curTime = Convertor.longOfString(currentResp.getHeader(TIME_HEADER_ONENIO),
                    -1, log);
            if (youngestTime == -2 || youngestTime < curTime) {
                youngestTime = curTime;
                youngestResponse = currentResp;
            }
        }

        sendResponseWithError(session, youngestResponse);
    }

    private void sendResponseWithError(HttpSession session, Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException ex) {
            log.error("IOException while sendResponse", ex);
            session.scheduleClose();
        }
    }

    private Response executeLocalMethod(String id, Request request, long timeSt) {
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                Entry<MemorySegment> result = dao.get(Convertor.stringToMemorySegment(id));
                if (result == null) {
                    return new Response("404", Response.EMPTY);

                } else if (Convertor.isValNull(result.value())) {
                    Response response = new Response("404", Response.EMPTY);
                    long time = Convertor.getTimeStamp(result.value());
                    response.addHeader(TIME_HEADER_ONENIO + time);
                    return response;

                } else {
                    long time = Convertor.getTimeStamp(result.value());
                    Response response = new Response("200",
                            Convertor.getValueNotNullAsBytes(result.value()));
                    response.addHeader(TIME_HEADER_ONENIO + time);
                    return response;
                }

            case Request.METHOD_PUT:
                try {
                    dao.upsert(Convertor.requestToEntry(id, request.getBody(), timeSt));
                } catch (IllegalStateException e) {
                    log.error("Exception during upsert (key: {})", id, e);
                    return new Response("409", Response.EMPTY);
                }

                Response putResp = new Response("201", Response.EMPTY);
                putResp.addHeader(TIME_HEADER_ONENIO + timeSt);
                return putResp;

            case Request.METHOD_DELETE:
                try {
                    dao.upsert(Convertor.requestToEntry(id, null, timeSt));
                } catch (IllegalStateException e) {
                    log.error("Exception during delete-upsert", e);
                    return new Response("409", Response.EMPTY);
                }

                Response deleteResp = new Response("202", Response.EMPTY);
                deleteResp.addHeader(TIME_HEADER_ONENIO + timeSt);
                return deleteResp;

            default:
                return new Response("405", Response.EMPTY);
        }
    }

    private Response executeRemoteMethod(String executorNode, Request request, long givenTime) {
        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header(INTERNAL_RQ_HEADER, "true")
                .timeout(Duration.ofSeconds(1));

        if (givenTime >= 0) {
            httpRequestBuilder = httpRequestBuilder.header(TIME_HEADER, Long.toString(givenTime));
        }

        HttpRequest httpRequest = httpRequestBuilder.build();



        try {
            HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            Response response = new Response(Integer.toString(httpResponse.statusCode()), httpResponse.body());

            Optional<String> timeStamp = httpResponse.headers().firstValue(TIME_HEADER);
            timeStamp.ifPresent(s -> response.addHeader(TIME_HEADER_ONENIO + s));

            return response;

        } catch (IOException e) {
            log.error("Error in internal request", e);
            return new Response(Response.BAD_GATEWAY, Response.EMPTY);
        } catch (InterruptedException e) {
            log.error("Error in internal request (INTERRUPTION)", e);
            Thread.currentThread().interrupt();
            return new Response(Response.BAD_GATEWAY, Response.EMPTY);
        }
    }

    private void applyRequestToExecutor(HttpSession session, ExecutorService executor,
                                        Callable<Response> method) throws IOException {
        try {
            executor.execute(() -> {
                try {
                    Response r = method.call();
                    session.sendResponse(r);
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    sendResponseWithError(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Exception during adding request to ExecServ queue", e);
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {

        if (!"/v0/entity".equals(request.getPath())) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }


        long time = System.currentTimeMillis();

        if (request.getHeader(INTERNAL_RQ_HEADER) == null) {
            // Request from outside
            int currAck = Convertor.intOfString(request.getParameter("ack="), defaultAck, log);
            int currFrom = Convertor.intOfString(request.getParameter("from="), defaultFrom, log);

            if (currFrom > clusterUrls.size() || currAck > currFrom || currAck < 1) {
                log.error("BAD ack or from parameter: [ack: {}, from: {}]", currAck, currFrom);
                session.sendError(Response.BAD_REQUEST, null);
                return;
            }

            String[] urls = getServerUrlsForKey(id, currFrom);
            List<CompletableFuture<Response>> responses = invokeAsyncAllRequests(id, request, urls, time);
            accumulateAndSendResults(responses, session, currAck, currFrom);

        } else {
            // Redirected request
            time = Convertor.longOfString(request.getHeader(TIME_HEADER_ONENIO), time, log);

            final long finalTime = time;
            applyRequestToExecutor(session, localExecutorService, () -> executeLocalMethod(id, request, finalTime));
        }
    }

    @Override
    public synchronized void start() {
        log.info("start server on url: {}", selfUrl);
        super.start();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        ServiceImpl.shutdownAndAwaitTermination(localExecutorService);
        ServiceImpl.shutdownAndAwaitTermination(remoteExecutorService);
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

}
