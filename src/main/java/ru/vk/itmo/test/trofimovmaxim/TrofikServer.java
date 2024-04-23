package ru.vk.itmo.test.trofimovmaxim;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.trofimovmaxim.dao.ReferenceBaseEntry;
import ru.vk.itmo.test.trofimovmaxim.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrofikServer extends HttpServer {

    private static final String HEADER_REMOTE = "X-flag-remote-reference-server-to-node-by-trofik";
    private static final String HEADER_REMOTE_ONE_NIO_HEADER = HEADER_REMOTE + ": da";
    static final String HEADER_TIMESTAMP = "X-flag-remote-reference-timestamp-trofik";
    private static final String HEADER_TIMESTAMP_ONE_NIO_HEADER = HEADER_TIMESTAMP + ": ";
    private static final Logger log = LoggerFactory.getLogger(TrofikServer.class);
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final int CLIENT_TIMEOUT = 500;

    private final ExecutorService executorLocal = Executors.newFixedThreadPool(
            THREADS / 2,
            new CustomThreadFactory("local-work"));
    private final ExecutorService executorRemote = Executors.newFixedThreadPool(
            THREADS / 2,
            new CustomThreadFactory("remote-work"));
    private final ReferenceDao dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public TrofikServer(ServiceConfig config,
                        ReferenceDao dao) throws IOException {
        super(createServerConfigWithPort(config.selfPort()));
        this.dao = dao;
        this.config = config;

        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(CLIENT_TIMEOUT))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static HttpServerConfig createServerConfigWithPort(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        serverConfig.selectors = Runtime.getRuntime().availableProcessors() / 2;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (!"/v0/entity".equals(request.getPath())) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (request.getMethod() != Request.METHOD_GET
                && request.getMethod() != Request.METHOD_DELETE
                && request.getMethod() != Request.METHOD_PUT) {
            session.sendError(Response.METHOD_NOT_ALLOWED, null);
            return;
        }

        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        if (request.getHeader(HEADER_REMOTE_ONE_NIO_HEADER) != null) {
            executeLocalRequest(request, session, id);
            return;
        }

        int ack = getInt(request, "ack=", config.clusterUrls().size() / 2 + 1);
        int from = getInt(request, "from=", config.clusterUrls().size());

        if (from <= 0 || from > config.clusterUrls().size() || ack > from || ack <= 0) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }

        int[] indexes = getIndexes(id, from);
        MergeHandleResult mergeHandleResult = new MergeHandleResult(session, indexes.length, ack);
        for (int i = 0; i < indexes.length; i++) {
            int index = indexes[i];
            String executorNode = config.clusterUrls().get(index);
            ResponseProcessor responseProcessor = new ResponseProcessor(mergeHandleResult, i);
            if (executorNode.equals(config.selfUrl())) {
                handleAsync(executorLocal, i,
                        mergeHandleResult, () -> responseProcessor.processResult(local(request, id)));
            } else {
                handleAsync(executorRemote, i,
                        mergeHandleResult, () -> remote(request, executorNode, responseProcessor));
            }
        }
    }

    private void executeLocalRequest(Request request, HttpSession session, String id) {
        executorLocal.execute(() -> {
            try {
                HandleResult local = local(request, id);
                Response response = new Response(String.valueOf(local.status()), local.data());
                response.addHeader(HEADER_TIMESTAMP_ONE_NIO_HEADER + local.timestamp());
                session.sendResponse(response);
            } catch (Exception e) {
                log.error("Exception during handleRequest", e);
                try {
                    session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                } catch (IOException ex) {
                    log.error("Exception while sending close connection", e);
                    session.scheduleClose();
                }
            }
        });
    }

    private int getInt(Request request, String param, int defaultValue) {
        int ack;
        String ackStr = request.getParameter(param);
        if (ackStr == null || ackStr.isBlank()) {
            ack = defaultValue;
        } else {
            try {
                ack = Integer.parseInt(ackStr);
            } catch (Exception e) {
                throw new BadParameterException("parse error of param");
            }
        }
        return ack;
    }

    private void remote(Request request, String executorNode, ResponseProcessor responseProcessor) {
        try {
            invokeRemote(executorNode, request, responseProcessor);
        } catch (IOException e) {
            log.info("I/O exception while calling remote node", e);
            responseProcessor.processResult(new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Thread interrupted");
            responseProcessor.processResult(new HandleResult(HttpURLConnection.HTTP_UNAVAILABLE, Response.EMPTY));
        }
    }

    private void handleAsync(ExecutorService executor,
                             int index, MergeHandleResult mergeHandleResult,
                             HandlerRunnable runnable) {
        try {
            executor.execute(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    log.error("Exception during handleRequest", e);
                    mergeHandleResult.add(index,
                            new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
                }
            });
        } catch (Exception e) {
            mergeHandleResult.add(index, new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }

    private void invokeRemote(String executorNode,
                              Request request,
                              ResponseProcessor responseProcessor) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header(HEADER_REMOTE, "da")
                .timeout(Duration.ofMillis(500))
                .build();
        CompletableFuture<HttpResponse<byte[]>> sending = httpClient.sendAsync(httpRequest,
                HttpResponse.BodyHandlers.ofByteArray()).whenComplete(responseProcessor);
        if (sending == null) {
            log.error("error while send async");
        }
    }

    private HandleResult local(Request request, String id) {
        long currentTimeMillis = System.currentTimeMillis();
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                ReferenceBaseEntry<MemorySegment> entry = dao.get(key);
                if (entry == null) {
                    return new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY);
                }
                if (entry.value() == null) {
                    return new HandleResult(HttpURLConnection.HTTP_NOT_FOUND, Response.EMPTY, entry.timestamp());
                }

                return new HandleResult(
                        HttpURLConnection.HTTP_OK,
                        entry.value().toArray(ValueLayout.JAVA_BYTE),
                        entry.timestamp());
            }
            case Request.METHOD_PUT -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new ReferenceBaseEntry<>(key, value, currentTimeMillis));
                return new HandleResult(HttpURLConnection.HTTP_CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                dao.upsert(new ReferenceBaseEntry<>(key, null, currentTimeMillis));
                return new HandleResult(HttpURLConnection.HTTP_ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new HandleResult(HttpURLConnection.HTTP_BAD_METHOD, Response.EMPTY);
            }
        }
    }

    // count <= config.clusterUrls().size()
    private int[] getIndexes(String id, int count) {
        int[] result = new int[count];
        int[] maxHashs = new int[count];

        for (int i = 0; i < count; i++) {
            String url = config.clusterUrls().get(i);
            int hash = Hash.murmur3(url + id);
            result[i] = i;
            maxHashs[i] = hash;
        }

        for (int i = count; i < config.clusterUrls().size(); i++) {
            String url = config.clusterUrls().get(i);
            int hash = Hash.murmur3(url + id);
            for (int j = 0; j < maxHashs.length; j++) {
                int maxHash = maxHashs[j];
                if (maxHash < hash) {
                    maxHashs[j] = hash;
                    result[j] = i;
                    break;
                }
            }
        }
        return result;
    }

    private interface HandlerRunnable {
        void run();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        TrofikService.shutdownAndAwaitTermination(executorLocal);
        TrofikService.shutdownAndAwaitTermination(executorRemote);
    }
}
