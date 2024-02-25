package ru.vk.itmo.test.solnyshkoksenia;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.DaoImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyHttpServer extends HttpServer {
    private final DaoImpl dao;
    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();
    private final ExecutorService executorService = new ThreadPoolExecutor(PROCESSORS, Integer.MAX_VALUE,
            5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), this::reject);

    public MyHttpServer(ServiceConfig config) throws IOException {
        super(createHttpServerConfig(config));
        this.dao = new DaoImpl(createConfig(config));
    }

    private static HttpServerConfig createHttpServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static Config createConfig(ServiceConfig config) {
        return new Config(config.workingDir(), Math.round(0.33 * 128 * 1024 * 1024)); // 0.33 * 128mb
    }

    private void reject(Runnable runnable, ExecutorService executorService) {
        HttpSession session = ((Task) runnable).session;
        try {
            session.sendResponse(new Response(Response.PAYMENT_REQUIRED, Response.EMPTY));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handleDefault(Request request, HttpSession session) {
        executorService.execute(new Task(() -> {
            try {
                if (request.getMethod() == Request.METHOD_GET ||
                        request.getMethod() == Request.METHOD_PUT ||
                        request.getMethod() == Request.METHOD_DELETE) {
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
        executorService.close();
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public void get(final HttpSession session,
                    @Param(value = "id", required = true) String id) {
        executorService.execute(new Task(() -> {
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
        }, session));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public void put(final Request request, final HttpSession session,
                    @Param(value = "id", required = true) String id) {
        executorService.execute(new Task(() -> {
            try {
                if (sendResponseIfEmpty(id, session)) {
                    return;
                }
                dao.upsert(new BaseEntry<>(toMS(id), MemorySegment.ofArray(request.getBody())));
                session.sendResponse(new Response(Response.CREATED, Response.EMPTY));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, session));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public void delete(final HttpSession session,
                       @Param(value = "id", required = true) String id) {
        executorService.execute(new Task(() -> {
            try {
                if (sendResponseIfEmpty(id, session)) {
                    return;
                }
                dao.upsert(new BaseEntry<>(toMS(id), null));
                session.sendResponse(new Response(Response.ACCEPTED, Response.EMPTY));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, session));
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
