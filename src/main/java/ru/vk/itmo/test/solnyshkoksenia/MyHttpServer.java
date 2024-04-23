package ru.vk.itmo.test.solnyshkoksenia;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.DaoImpl;
import ru.vk.itmo.test.solnyshkoksenia.dao.EntryExtended;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MyHttpServer extends HttpServer {
    private static final int TIMEOUT = 500;
    private static final String HEADER_TIMESTAMP = "X-timestamp";
    private static final String HEADER_TIMESTAMP_HEADER = HEADER_TIMESTAMP + ": ";
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final int MAX_POOL_SIZE = THREADS * 2;
    private static final int KEEP_ALIVE = 5;
    private static final int QUEUE_CAPACITY = 128;
    //    private static final int CHUNK_BYTE_SIZE = 1024 * 1024;
    private final ServiceConfig config;
    private final DaoImpl dao;
    private final HttpClient httpClient;
    private final ExecutorService executorService = new ThreadPoolExecutor(THREADS, MAX_POOL_SIZE,
            KEEP_ALIVE, TimeUnit.SECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            new CustomThreadFactory("local-work"),
            (r, executor) -> {
                HttpSession session = ((Task) r).session;
                try {
                    session.sendResponse(new Response(Response.PAYMENT_REQUIRED, Response.EMPTY));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

    public MyHttpServer(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        this.config = config;
        this.dao = new DaoImpl(createConfig(config));
        this.httpClient = HttpClient.newBuilder()
                .executor(Executors.newFixedThreadPool(THREADS))
                .connectTimeout(Duration.ofMillis(TIMEOUT))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;
        serverConfig.selectors = THREADS / 2;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static Config createConfig(ServiceConfig config) {
        return new Config(config.workingDir(), Math.round(0.33 * 128 * 1024 * 1024));
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executorService.close();
        httpClient.close();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        executorService.execute(new Task(() -> {
            try {
                if (request.getMethod() == Request.METHOD_GET
                        || request.getMethod() == Request.METHOD_PUT
                        || request.getMethod() == Request.METHOD_DELETE) {
                    session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                    return;
                }
                session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, session));
    }

    @Path("/v0/entity")
    public void handleRequest(final Request request, final HttpSession session,
                              @Param(value = "id", required = true) String id, @Param(value = "ack") String ackString,
                              @Param(value = "from") String fromString, @Param(value = "local") String local)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {
        int ack = config.clusterUrls().size() / 2 + 1;
        if (ackString != null && !ackString.isBlank()) {
            try {
                ack = Integer.parseInt(ackString);
            } catch (NumberFormatException e) {
                session.sendError(Response.BAD_REQUEST, "Invalid ack parameter");
                return;
            }
        }

        int from = config.clusterUrls().size();
        if (fromString != null && !fromString.isBlank()) {
            try {
                from = Integer.parseInt(fromString);
            } catch (NumberFormatException e) {
                session.sendError(Response.BAD_REQUEST, "Invalid from parameter");
                return;
            }
        }

        if (id.isBlank() || ack < 1 || ack > from || from > config.clusterUrls().size()) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return;
        }

        if (local != null) {
            CompletableFuture<Response> response = invokeLocal(request, id);
            session.sendResponse(response.get(TIMEOUT, TimeUnit.MILLISECONDS));
            return;
        }
        handle(request, id, session, ack, from);
    }

    private void handle(Request request, String id, HttpSession session, Integer ack, Integer from) {
        List<String> executorNodes = getNodesByEntityId(id, from);
        List<CompletableFuture<Response>> responses = new ArrayList<>();

        for (String node : executorNodes) {
            if (node.equals(config.selfUrl())) {
                responses.add(invokeLocal(request, id));
            } else {
                try {
                    responses.add(invokeRemote(node, request));
                } catch (IOException e) {
                    responses.add(CompletableFuture.completedFuture(new Response(Response.INTERNAL_ERROR,
                            Response.EMPTY)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    responses.add(CompletableFuture.completedFuture(new Response(Response.SERVICE_UNAVAILABLE,
                            Response.EMPTY)));
                }
            }
        }

        executorService.execute(() -> {
            List<Response> completedResponses = new ArrayList<>();
            for (CompletableFuture<Response> response : responses) {
                try {
                    completedResponses.add(response.get(TIMEOUT, TimeUnit.MILLISECONDS));
                    if (completedResponses.size() > ack) {
                        try {
                            sendResponse(request, session, completedResponses, ack);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        return;
                    }
                } catch (ExecutionException | TimeoutException e) {
                    completedResponses.add(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    completedResponses.add(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
                }
            }
            try {
                sendResponse(request, session, completedResponses, ack);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void sendResponse(Request request, HttpSession session, List<Response> responses, int ack)
            throws IOException {
        List<Integer> statuses = responses.stream().map(Response::getStatus).toList();
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                if (statuses.stream().filter(s -> s == HttpURLConnection.HTTP_OK
                        || s == HttpURLConnection.HTTP_NOT_FOUND).count() < ack) {
                    session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
                    return;
                }

                if (statuses.stream().noneMatch(s -> s == HttpURLConnection.HTTP_OK)) {
                    session.sendResponse(new Response(Response.NOT_FOUND, Response.EMPTY));
                    return;
                }

                responses = responses.stream().filter(r -> r.getStatus() == HttpURLConnection.HTTP_OK
                        || r.getStatus() == HttpURLConnection.HTTP_NOT_FOUND).toList();

                Response bestResp = responses.getFirst();
                for (int i = 1; i < responses.size(); i++) {
                    String bestRespTime = bestResp.getHeader(HEADER_TIMESTAMP_HEADER);
                    if (responses.get(i).getHeader(HEADER_TIMESTAMP) != null) {
                        if (bestRespTime == null || Long.parseLong(responses.get(i).getHeader(HEADER_TIMESTAMP_HEADER))
                                > Long.parseLong(bestRespTime)) {
                            bestResp = responses.get(i);
                        }
                    }
                }
                session.sendResponse(bestResp);
            }
            case Request.METHOD_PUT -> {
                if (statuses.stream().filter(s -> s == HttpURLConnection.HTTP_CREATED).count() < ack) {
                    session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
                    return;
                }
                session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
            }
            case Request.METHOD_DELETE -> {
                if (statuses.stream().filter(s -> s == HttpURLConnection.HTTP_ACCEPTED).count() < ack) {
                    session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
                    return;
                }
                session.sendResponse(new Response(Response.ACCEPTED, Response.EMPTY));
            }
            default -> session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<Response> invokeRemote(String executorNode, Request request)
            throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI() + "&local=1"))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .timeout(Duration.ofMillis(TIMEOUT))
                .build();
        CompletableFuture<Response> response = new CompletableFuture<>();
        httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((httpResponse, throwable) -> response.complete(makeResponse(httpResponse)));
        return response;
    }

    private Response makeResponse(HttpResponse<byte[]> httpResponse) {
        Optional<String> header = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
        long timestamp;
        if (header.isPresent()) {
            try {
                timestamp = Long.parseLong(header.get());
            } catch (Exception e) {
                timestamp = 0;
            }
        } else {
            timestamp = 0;
        }
        Response response = new Response(Integer.toString(httpResponse.statusCode()), httpResponse.body());
        response.addHeader(HEADER_TIMESTAMP_HEADER + timestamp);
        return response;
    }

    private CompletableFuture<Response> invokeLocal(Request request, String id) {
        CompletableFuture<Response> cf = new CompletableFuture<>();
        cf.completeAsync(() -> {
            switch (request.getMethod()) {
                case Request.METHOD_GET -> {
                    MemorySegment key = toMS(id);
                    Entry<MemorySegment> entry = dao.get(key);
                    if (entry == null) {
                        return new Response(Response.NOT_FOUND, Response.EMPTY);
                    }

                    if (entry.value() == null) {
                        Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                        response.addHeader(HEADER_TIMESTAMP_HEADER + ((EntryExtended<MemorySegment>) entry)
                                .timestamp().get(ValueLayout.JAVA_LONG_UNALIGNED, 0));
                        return response;
                    }

                    Response response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
                    response.addHeader(HEADER_TIMESTAMP_HEADER + ((EntryExtended<MemorySegment>) entry)
                            .timestamp().get(ValueLayout.JAVA_LONG_UNALIGNED, 0));
                    return response;
                }
                case Request.METHOD_PUT -> {
                    MemorySegment key = toMS(id);
                    MemorySegment value = MemorySegment.ofArray(request.getBody());
                    dao.upsert(new BaseEntry<>(key, value));
                    return new Response(Response.CREATED, Response.EMPTY);
                }
                case Request.METHOD_DELETE -> {
                    MemorySegment key = toMS(id);
                    dao.upsert(new BaseEntry<>(key, null));
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                }
                default -> {
                    return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
                }
            }
        });
        return cf;
    }

    private MemorySegment toMS(String input) {
        return MemorySegment.ofArray(input.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> getNodesByEntityId(String id, Integer from) {
        List<Node> executorNodes = new ArrayList<>();

        for (int i = 0; i < config.clusterUrls().size(); i++) {
            int hash = Hash.murmur3(config.clusterUrls().get(i) + id);
            executorNodes.add(new Node(i, hash));
        }
        executorNodes.sort(Node::compareTo);

        List<String> nodesId = new ArrayList<>();
        for (int i = 0; i < from; i++) {
            nodesId.add(config.clusterUrls().get(executorNodes.get(i).id));
        }
        return nodesId;
    }

    @SuppressWarnings("unused")
    private record Node(int id, int hash) implements Comparable<Node> {

        @Override
        public int compareTo(Node o) {
            return Integer.compare(hash, o.hash);
        }
    }

    public static class Task implements Runnable {
        private final Runnable runnable;
        private final HttpSession session;

        public Task(Runnable runnable, HttpSession session) {
            this.runnable = runnable;
            this.session = session;
        }

        @Override
        public void run() {
            runnable.run();
        }
    }
}
