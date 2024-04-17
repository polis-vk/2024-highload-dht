package ru.vk.itmo.test.emelyanovvitaliy;

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
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DhtServer extends HttpServer {
    public static final String NOT_ENOUGH_REPLICAS_STATUS = "504 Not Enough Replicas";
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    public static final String ID_KEY = "id=";
    protected static final int THREADS_PER_PROCESSOR = 1;
    protected static final byte[] EMPTY_BODY = new byte[0];
    protected static final long KEEP_ALIVE_TIME_MILLIS = 1000;
    protected static final int REQUEST_TIMEOUT_MILLIS = 1024;
    protected static final int THREAD_POOL_TERMINATION_TIMEOUT_SECONDS = 600;
    protected static final int TASK_QUEUE_SIZE = Runtime.getRuntime().availableProcessors() * THREADS_PER_PROCESSOR;
    protected final MergeDaoMediator mergeDaoMediator;
    protected final ThreadPoolExecutor threadPoolExecutor;

    public DhtServer(ServiceConfig config) throws IOException {
        super(createConfig(config));
        mergeDaoMediator = new MergeDaoMediator(config.workingDir(), config.selfUrl(), config.clusterUrls());
        AtomicInteger threadsInPool = new AtomicInteger(0);
        threadPoolExecutor = new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors() * THREADS_PER_PROCESSOR,
                Runtime.getRuntime().availableProcessors() * THREADS_PER_PROCESSOR,
                KEEP_ALIVE_TIME_MILLIS,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(TASK_QUEUE_SIZE),
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("DhtServerThreadPool-Thread-" + threadsInPool.incrementAndGet());
                    return t;
                }
        );
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            threadPoolExecutor.shutdown();
            if (!threadPoolExecutor.awaitTermination(THREAD_POOL_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                threadPoolExecutor.shutdownNow();
            }
            mergeDaoMediator.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedInterruptedException(e);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @RequestMethod(METHOD_GET)
    @Path("/v0/entity")
    public void entity(HttpSession session, Request request) throws IOException {
        String id = request.getParameter(ID_KEY);
        requestProccessing(id, session,
                () -> {
                    try {
                        CompletableFuture<TimestampedEntry<MemorySegment>> future = mergeDaoMediator.get(request);
                        future.whenCompleteAsync(
                                (entry, ex) -> {
                                    try {
                                        if (entry == null) {
                                            sendNotEnoughReplicas(session);
                                        } else if (entry.timestamp() == DaoMediator.NEVER_TIMESTAMP) {
                                            session.sendResponse(new Response(Response.NOT_FOUND, EMPTY_BODY));
                                        } else {
                                            Response response;
                                            if (entry.value() == null) {
                                                response = new Response(Response.NOT_FOUND, EMPTY_BODY);
                                            } else {
                                                response = new Response(
                                                        Response.OK,
                                                        ((Entry<MemorySegment>) entry).value()
                                                                .toArray(ValueLayout.JAVA_BYTE)
                                                );
                                            }
                                            response.addHeader(TIMESTAMP_HEADER + ": " + entry.timestamp());
                                            session.sendResponse(response);
                                        }
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                }, threadPoolExecutor
                        );
                    } catch (IllegalArgumentException e) {
                        sendBadRequestResponseUnchecked(session);
                    }
                }
        );
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @RequestMethod(METHOD_PUT)
    @Path("/v0/entity")
    public void putEntity(@Param(value = "id") String id, HttpSession httpSession, Request request) throws IOException {
        requestProccessing(id, httpSession,
                () -> {
                    try {
                        mergeDaoMediator.put(request).whenCompleteAsync(
                                (success, ex) -> {
                                    try {
                                        if (success) {
                                            httpSession.sendResponse(new Response(Response.CREATED, EMPTY_BODY));
                                        } else {
                                            sendNotEnoughReplicas(httpSession);
                                        }
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                }, threadPoolExecutor
                        );
                    } catch (IllegalArgumentException e) {
                        sendBadRequestResponseUnchecked(httpSession);
                    }
                }
        );
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @RequestMethod(METHOD_DELETE)
    @Path("/v0/entity")
    public void deleteEntity(@Param("id") String id, HttpSession httpSession, Request request) throws IOException {
        requestProccessing(id, httpSession,
                () -> {
                    try {
                        mergeDaoMediator.delete(request).whenComplete(
                                (success, ex) -> {
                                    try {
                                        if (success) {
                                            httpSession.sendResponse(new Response(Response.ACCEPTED, EMPTY_BODY));
                                        } else {
                                            sendNotEnoughReplicas(httpSession);
                                        }
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                }
                        );
                    } catch (IllegalArgumentException e) {
                        sendBadRequestResponseUnchecked(httpSession);
                    }
                }
        );
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        int requestMethod = request.getMethod();
        if (requestMethod == METHOD_GET || requestMethod == METHOD_PUT || requestMethod == METHOD_DELETE) {
            sendBadRequestResponse(session);
        } else {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, EMPTY_BODY));
        }
    }

    private void requestProccessing(String id, HttpSession session, Runnable runnable) throws IOException {
        if (isKeyIncorrect(id)) {
            sendBadRequestResponse(session);
        } else {
            long startTimeInMillis = System.currentTimeMillis();
            threadPoolExecutor.execute(
                    () -> {
                        try {
                            if (System.currentTimeMillis() - startTimeInMillis > REQUEST_TIMEOUT_MILLIS) {
                                session.sendResponse(new Response(Response.PAYMENT_REQUIRED, EMPTY_BODY));
                                return;
                            }
                            runnable.run();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
            );
        }
    }

    private static void sendNotEnoughReplicas(HttpSession session) throws IOException {
        session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_STATUS, EMPTY_BODY));
    }

    private static void sendBadRequestResponse(HttpSession httpSession) throws IOException {
        httpSession.sendResponse(new Response(Response.BAD_REQUEST, EMPTY_BODY));
    }

    private void sendBadRequestResponseUnchecked(HttpSession session) {
        try {
            sendBadRequestResponse(session);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isKeyIncorrect(String key) {
        return key == null || key.isEmpty();
    }

    private static HttpServerConfig createConfig(ServiceConfig serviceConfig) {
        HttpServerConfig config = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;
        config.acceptors = new AcceptorConfig[] {acceptorConfig};
        config.keepAlive = 1000;
        config.closeSessions = true;
        return config;
    }
}
