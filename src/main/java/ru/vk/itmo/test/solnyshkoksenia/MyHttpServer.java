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
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyHttpServer extends HttpServer {
    private final static int THREADS = Runtime.getRuntime().availableProcessors();
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
    private final ExecutorService executorRemote = Executors.newFixedThreadPool(THREADS / 2,
            new CustomThreadFactory("remote-work"));

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
        return new Config(config.workingDir(), Math.round(0.33 * 128 * 1024 * 1024));
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

    @Override
    public synchronized void stop() {
        super.stop();
        executorLocal.close();
        executorRemote.close();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handleAsync(ExecutorService executor, Runnable runnable) {
        executor.execute(runnable);
    }

    private void handle(Request request, String id, Runnable runnable, HttpSession session) {
        String executorNode = getNodeByEntityId(id);
        if (executorNode.equals(config.selfUrl())) {
            handleAsync(executorLocal, new Task(runnable, session));
        } else {
            handleAsync(executorRemote, () -> {
                try {
                    handleRemote(executorNode, request, session);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private void handleRemote(String executorNode, Request request, HttpSession session) throws IOException {
        try {
            Response response = invokeRemote(executorNode, request);
            session.sendResponse(response);
        } catch (IOException e) {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
        }
    }

    private Response invokeRemote(String executorNode, Request request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .timeout(Duration.ofMillis(500))
                .build();
        HttpResponse<byte[]> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(Integer.toString(httpResponse.statusCode()), httpResponse.body());
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public void get(final Request request, final HttpSession session,
                    @Param(value = "id", required = true) String id) {
        Runnable runnable = () -> {
            try {
                if (sendResponseIfEmpty(id, session)) {
                    return;
                }

                Entry<MemorySegment> entry = dao.get(toMS(id));
                if (entry == null) {
                    session.sendResponse(new Response(Response.NOT_FOUND, Response.EMPTY));
                    return;
                }
                session.sendResponse(Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        handle(request, id, runnable, session);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public void put(final Request request, final HttpSession session,
                    @Param(value = "id", required = true) String id) {
        Runnable runnable = () -> {
            try {
                if (sendResponseIfEmpty(id, session)) {
                    return;
                }
                dao.upsert(new BaseEntry<>(toMS(id), MemorySegment.ofArray(request.getBody())));
                session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        handle(request, id, runnable, session);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public void delete(final Request request, final HttpSession session,
                       @Param(value = "id", required = true) String id) {
        Runnable runnable = () -> {
            try {
                if (sendResponseIfEmpty(id, session)) {
                    return;
                }
                dao.upsert(new BaseEntry<>(toMS(id), null));
                session.sendResponse(new Response(Response.ACCEPTED, Response.EMPTY));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        handle(request, id, runnable, session);
    }

    private boolean sendResponseIfEmpty(String input, final HttpSession session) throws IOException {
        if (input.isBlank()) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            return true;
        }
        return false;
    }

    private MemorySegment toMS(String input) {
        return MemorySegment.ofArray(input.getBytes(StandardCharsets.UTF_8));
    }

    private String getNodeByEntityId(String id) {
        int nodeId = 0;
        int maxHash = Hash.murmur3(config.clusterUrls().getFirst() + id);
        for (int i = 1; i < config.clusterUrls().size(); i++) {
            String url = config.clusterUrls().get(i);
            int result = Hash.murmur3(url + id);
            if (maxHash < result) {
                maxHash = result;
                nodeId = i;
            }
        }
        return config.clusterUrls().get(nodeId);
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
