package ru.vk.itmo.test.solnyshkoksenia;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MyHttpServer extends HttpServer {
    private static final long FLUSHING_THRESHOLD_BYTES = Math.round(0.33 * 128 * 1024 * 1024);
    private static final String HEADER_TIMESTAMP = "X-timestamp";
    private static final String HEADER_TIMESTAMP_HEADER = HEADER_TIMESTAMP + ": ";
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private final ServiceConfig config;
    private final DaoImpl dao;
    private final HttpClient httpClient;
    private final ExecutorService executorLocal = new ThreadPoolExecutor(THREADS, THREADS * 2,
            5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(128),
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
                .connectTimeout(Duration.ofMillis(500))
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
        return new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        executorLocal.close();
        httpClient.close();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        executorLocal.execute(new Task(() -> {
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

    @Path("/v0/entities")
    @RequestMethod(Request.METHOD_GET)
    public void handleRangeRequest(final Request request, final HttpSession session,
                                   @Param(value = "start", required = true) String start,
                                   @Param(value = "end") String end, @Param(value = "cluster") String cluster)
            throws IOException {
        if (!ServerUtils.validParameters(session, start, end)) {
            return;
        }

        if (cluster != null) {
            sendClusterRange((CustomHttpSession) session, request, start, end);
            return;
        }

        executorLocal.execute(() -> {
            try {
                sendLocalRange((CustomHttpSession) session, start, end);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private Iterator<Entry<MemorySegment>> invokeLocalRange(String start, String end) {
        return dao.get(ServerUtils.toMS(start), end == null || end.isBlank() ? null : ServerUtils.toMS(end));
    }

    private void sendLocalRange(CustomHttpSession session, String start, String end) throws IOException {
        Iterator<Entry<MemorySegment>> range = invokeLocalRange(start, end);
        session.stream(range);
    }

    private void sendClusterRange(CustomHttpSession session, Request request, String start, String end) {
        List<CompletableFuture<Response>> responses = getResponses(request, config.clusterUrls(), request.getURI().replace("&cluster=1", ""),
                responseInfo -> new CustomSubscriber());
        Iterator<Entry<MemorySegment>> localIterator = invokeLocalRange(start, end);

        executorLocal.execute(() -> {
            List<Response> completedResponses = responses
                    .stream()
                    .map(cf -> {
                        try {
                            return cf.get(5000, TimeUnit.MILLISECONDS);
                        } catch (ExecutionException | TimeoutException e) {
                            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
                        }
                    }).toList();
            try {
                sendRangeResponse(session, localIterator, completedResponses);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private void sendRangeResponse(CustomHttpSession session, Iterator<Entry<MemorySegment>> firstIterator,
                                   List<Response> responses) throws IOException {
        Iterator<Entry<MemorySegment>> iterator = MergeRangeResult.range(firstIterator, responses.stream()
                .filter(r -> r.getStatus() == HttpURLConnection.HTTP_OK).toList());
        session.stream(iterator);
    }

    @Path("/v0/entity")
    public void handleRequest(final Request request, final HttpSession session,
                              @Param(value = "id", required = true) String id, @Param(value = "ack") String ackString,
                              @Param(value = "from") String fromString, @Param(value = "local") String local)
            throws IOException {
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
            Response response = invokeLocal(request, id);
            session.sendResponse(response);
            return;
        }
        handle(request, id, session, ack, from);
    }

    private List<CompletableFuture<Response>> getResponses(Request request, List<String> executorNodes, String uri,
                                                           HttpResponse.BodyHandler<byte[]> responseBodyHandler) {
        List<CompletableFuture<Response>> responses = new ArrayList<>();
        for (String node : executorNodes) {
            if (!node.equals(config.selfUrl())) {
                try {
                    responses.add(invokeRemote(node, request, uri, responseBodyHandler));
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
        return responses;
    }

    private void handle(Request request, String id, HttpSession session, Integer ack, Integer from) {
        List<String> executorNodes = ServerUtils.getNodesByEntityId(config.clusterUrls(), id, from);
        List<CompletableFuture<Response>> responses = getResponses(request, executorNodes,
                request.getURI() + "&local=1", HttpResponse.BodyHandlers.ofByteArray());
        if (executorNodes.contains(config.selfUrl())) {
            responses.add(CompletableFuture.completedFuture(invokeLocal(request, id)));
        }

        executorLocal.execute(() -> {
            List<Response> completedResponses = responses
                    .stream()
                    .map(cf -> {
                        try {
                            return cf.get(500, TimeUnit.MILLISECONDS);
                        } catch (ExecutionException | TimeoutException e) {
                            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
                        }
                    }).toList();
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

                session.sendResponse(getBestResponse(responses));
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

    private static Response getBestResponse(List<Response> responses) {
        responses = responses.stream().filter(r -> r.getStatus() == HttpURLConnection.HTTP_OK
                || r.getStatus() == HttpURLConnection.HTTP_NOT_FOUND).toList();

        Response bestResp = responses.getFirst();
        for (int i = 1; i < responses.size(); i++) {
            String bestRespTime = bestResp.getHeader(HEADER_TIMESTAMP_HEADER);
            if (responses.get(i).getHeader(HEADER_TIMESTAMP) != null && (bestRespTime == null ||
                    Long.parseLong(responses.get(i).getHeader(HEADER_TIMESTAMP_HEADER))
                            > Long.parseLong(bestRespTime))) {
                bestResp = responses.get(i);
            }
        }
        return bestResp;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<Response> invokeRemote(String executorNode, Request request, String uri,
                                                     HttpResponse.BodyHandler<byte[]> responseBodyHandler)
            throws IOException, InterruptedException {
        HttpRequest httpRequest = ServerUtils.buildHttpRequest(executorNode, request, uri);
        CompletableFuture<Response> response = new CompletableFuture<>();
        httpClient.sendAsync(httpRequest, responseBodyHandler)
                .whenComplete((httpResponse, throwable) -> response.complete(ServerUtils.makeResponse(httpResponse)));
        return response;
    }

    private Response invokeLocal(Request request, String id) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                MemorySegment key = ServerUtils.toMS(id);
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
                MemorySegment key = ServerUtils.toMS(id);
                MemorySegment value = MemorySegment.ofArray(request.getBody());
                dao.upsert(new BaseEntry<>(key, value));
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                MemorySegment key = ServerUtils.toMS(id);
                dao.upsert(new BaseEntry<>(key, null));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new CustomHttpSession(socket, this);
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
