package ru.vk.itmo.test.reference;

import one.nio.async.CustomThreadFactory;
import one.nio.http.*;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import one.nio.util.Hash;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.reference.dao2.ReferenceBaseEntry;
import ru.vk.itmo.test.reference.dao2.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReferenceServer extends HttpServer {

    private static final String HEADER_REMOTE = "X-nodep";
    private static final String HEADER_REMOTE_ONE_NIO_HEADER = HEADER_REMOTE + ": da";
    private static final String HEADER_TIMESTAMP = "X-rmfp";
    private static final String HEADER_TIMESTAMP_ONE_NIO_HEADER = HEADER_TIMESTAMP + ": ";
    private static final Logger log = LoggerFactory.getLogger(ReferenceServer.class);
    private final static int THREADS = Runtime.getRuntime().availableProcessors();

    private final ExecutorService executorLocal = Executors.newFixedThreadPool(THREADS, new CustomThreadFactory("local-work"));
    private final ExecutorService executorWork = Executors.newFixedThreadPool(THREADS, new CustomThreadFactory("work-thread"));
    private final ExecutorService clientExecutor = Executors.newFixedThreadPool(THREADS, new CustomThreadFactory("client-executor"));
    private final ExecutorService responseExecutor = Executors.newFixedThreadPool(THREADS, new CustomThreadFactory("response-executor"));

    private final ExecutorService executorMerge = Executors.newFixedThreadPool(THREADS);
    ExecutorService executorService;
    private final ReferenceDao dao;
    private final ServiceConfig config;
    private final HttpClient httpClient;

    public ReferenceServer(ServiceConfig config,
                           ReferenceDao dao) throws IOException {
        super(createServerConfigWithPort(config.selfPort()));
        this.dao = dao;
        this.config = config;

        executorService = Executors.newSingleThreadExecutor();


        this.httpClient = HttpClient.newBuilder()
                .executor(clientExecutor)
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static HttpServerConfig createServerConfigWithPort(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
//        acceptorConfig.threads = Runtime.getRuntime().availableProcessors() / 2;
        serverConfig.selectors = Runtime.getRuntime().availableProcessors() / 2;
//        serverConfig.keepAlive = 60_000;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession sessionI) throws IOException {
        if (!(sessionI instanceof ReferenceHttpSession session)) {
            throw new IllegalArgumentException("this method support only ReferenceHttpSession");
        }
        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            session.sendError(Response.BAD_REQUEST, null);
            return;
        }
        if (request.getHeader(HEADER_REMOTE_ONE_NIO_HEADER) != null) {

            executorLocal.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        HandleResult local = localInternal(request, id);

                        Response response = new Response(String.valueOf(local.status()), local.data());
                        response.addHeader(HEADER_TIMESTAMP_ONE_NIO_HEADER + local.timestamp());
                        session.sendResponseOrClose(response);
                    } catch (Exception e) {
                        session.sendError(e);
                    }
                }
            });


            return;
        }
        executorWork.execute(new Runnable() {
            @Override
            public void run() {

                try {


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


                    int ack = getInt(request, "ack=", config.clusterUrls().size() / 2 + 1);
                    int from = getInt(request, "from=", config.clusterUrls().size());

                    if (from <= 0 || from > config.clusterUrls().size() || ack > from || ack <= 0) {
                        session.sendError(Response.BAD_REQUEST, "Illegal argument ack/from " + ack + "/" + from);
                        return;
                    }


                    int[] indexes = getIndexes(id, from); //todo ???
                    MergeHandleResult mergeHandleResult = new MergeHandleResult(session, executorMerge, indexes.length, ack);
                    for (int i = 0; i < indexes.length; i++) {
                        int index = indexes[i];
                        String executorNode = config.clusterUrls().get(index);
                        if (executorNode.equals(config.selfUrl())) {
                            handle(i, mergeHandleResult, () -> local(request, id));
                        } else {
                            handle(i, mergeHandleResult, () -> remote(request, executorNode));
                        }
                    }
                } catch (Exception e) {
                    session.sendError(e);
                }
            }

        });
    }

    private int getInt(Request request, String param, int defaultValue) throws IOException {
        int ack;
        String ackStr = request.getParameter(param);
        if (ackStr == null || ackStr.isBlank()) {
            ack = defaultValue;
        } else {
            try {
                ack = Integer.parseInt(ackStr);
            } catch (Exception e) {
                // todo ваша ошибка
                throw new IllegalArgumentException("parse error");
            }
        }
        return ack;
    }

    private CompletableFuture<HandleResult> remote(Request request, String executorNode) {
        return invokeRemote(executorNode, request);
    }

    private void handle(int index, MergeHandleResult mergeHandleResult, ERunnable runnable) {
        try {
            CompletableFuture<HandleResult> handleResult = runnable.run();
            mergeHandleResult.add(index, handleResult);
        } catch (Exception e) {
            mergeHandleResult.add(index, CompletableFuture.failedFuture(e));
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    private CompletableFuture<HandleResult> invokeRemote(String executorNode, Request request) {
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

        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        return future.thenApplyAsync(httpResponse -> {
            Optional<String> string = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
            long timestamp;
            if (string.isPresent()) {
                try {
                    timestamp = Long.parseLong(string.get());
                } catch (Exception e) {
                    log.error("todo ");
                    timestamp = 0;
                }
            } else {
                timestamp = 0;
            }
            return new HandleResult(httpResponse.statusCode(), httpResponse.body(), timestamp);
        }, responseExecutor);

    }

    private CompletableFuture<HandleResult> local(Request request, String id) {
        return CompletableFuture.supplyAsync(() -> localInternal(request, id), executorLocal);
    }

    private HandleResult localInternal(Request request, String id) {
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

                return new HandleResult(HttpURLConnection.HTTP_OK, entry.value().toArray(ValueLayout.JAVA_BYTE), entry.timestamp());
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
        assert count < 5;

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

    //todo  naming
    private interface ERunnable {
        CompletableFuture<HandleResult> run() throws Exception;
    }

    @Override
    public synchronized void stop() {
        super.stop();
        ReferenceService.shutdownAndAwaitTermination(executorLocal);
        ReferenceService.shutdownAndAwaitTermination(executorWork);
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new ReferenceHttpSession(socket, this);
    }
}