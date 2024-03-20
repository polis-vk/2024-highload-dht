package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DhtServer extends HttpServer {
    public static final byte[] EMPTY_BODY = new byte[0];
    public static final int THREADS_PER_PROCESSOR = 2;
    protected final HttpClient[] httpClients;
    public static final long KEEP_ALIVE_TIME_MILLIS = 1000;
    public static final int REQUEST_TIMEOUT_MILLIS = 1024;
    public static final int FLUSH_THRESHOLD_BYTES = 1 << 24; // 16 MiB
    public static final int THREAD_POOL_TERMINATION_TIMEOUT_SECONDS = 600;
    public static final int TASK_QUEUE_SIZE = Runtime.getRuntime().availableProcessors() * THREADS_PER_PROCESSOR;
    public final AtomicInteger threadsInPool = new AtomicInteger(0);
    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
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
    private final ReferenceDao dao;

    public DhtServer(ServiceConfig config) throws IOException {
        super(createConfig(config));
        httpClients = getHttpClients(config.clusterUrls(), config.selfUrl());
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            threadPoolExecutor.shutdown();
            if (!threadPoolExecutor.awaitTermination(THREAD_POOL_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new UncheckedTimeoutException("Waited too lot to stop the thread pool");
            }
            for (HttpClient httpClient : httpClients) {
                if (httpClient != null && !httpClient.isClosed()) {
                    httpClient.close();
                }
            }
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedInterruptedException(e);
        }
    }

    @RequestMethod(METHOD_GET)
    @Path("/v0/entity")
    public void entity(@Param(value = "id") String id, HttpSession session, Request request) throws IOException {
        if (isKeyIncorrect(id)) {
            sendBadRequestResponse(session);
        } else {
            long startTimeInMillis = System.currentTimeMillis();
            threadPoolExecutor.execute(
                    () -> {
                        try {
                            if (System.currentTimeMillis() - startTimeInMillis > REQUEST_TIMEOUT_MILLIS) {
                                session.sendResponse(new Response(Response.PAYMENT_REQUIRED, EMPTY_BODY));
                            } else if (!tryForward(session, request, id)) {
                                Entry<MemorySegment> entry = dao.get(keyFor(id));
                                if (entry == null) {
                                    session.sendResponse(new Response(Response.NOT_FOUND, EMPTY_BODY));
                                } else {
                                    session.sendResponse(new Response(Response.OK, valueFor(entry)));
                                }
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
            );
        }
    }

    private HttpClient getHttpClientByKey(String key) {
        return httpClients[Math.abs(key.hashCode()) % httpClients.length];
    }

    private boolean tryForward(HttpSession session, Request request, String key) throws IOException {
        HttpClient httpClient = getHttpClientByKey(key);
        if (httpClient == null) {
            return false;
        }
        try {
            session.sendResponse(httpClient.invoke(request));
        } catch (PoolException | HttpException e) {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, EMPTY_BODY));
        } catch (InterruptedException e) {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, EMPTY_BODY));
            Thread.currentThread().interrupt();
        }
        return true;
    }

    @RequestMethod(METHOD_PUT)
    @Path("/v0/entity")
    public void putEntity(@Param(value = "id") String id, HttpSession httpSession, Request request) throws IOException {
        if (isKeyIncorrect(id) || request.getBody() == null) {
            sendBadRequestResponse(httpSession);
        } else {
            long startTimeInMillis = System.currentTimeMillis();
            threadPoolExecutor.execute(
                    () -> {
                        try {
                            if (System.currentTimeMillis() - startTimeInMillis > REQUEST_TIMEOUT_MILLIS) {
                                httpSession.sendResponse(new Response(Response.PAYMENT_REQUIRED, EMPTY_BODY));
                                return;
                            }
                            if (!tryForward(httpSession, request, id)) {
                                dao.upsert(new BaseEntry<>(keyFor(id), MemorySegment.ofArray(request.getBody())));
                                httpSession.sendResponse(new Response(Response.CREATED, EMPTY_BODY));
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
            );
        }
    }

    @RequestMethod(METHOD_DELETE)
    @Path("/v0/entity")
    public void deleteEntity(@Param("id") String id, HttpSession httpSession, Request request) throws IOException {
        if (isKeyIncorrect(id)) {
            sendBadRequestResponse(httpSession);
        } else {
            long startTimeInMillis = System.currentTimeMillis();
            threadPoolExecutor.execute(
                    () -> {
                        try {
                            if (System.currentTimeMillis() - startTimeInMillis > REQUEST_TIMEOUT_MILLIS) {
                                httpSession.sendResponse(new Response(Response.PAYMENT_REQUIRED, EMPTY_BODY));
                                return;
                            }
                            if (!tryForward(httpSession, request, id)) {
                                dao.upsert(new BaseEntry<>(keyFor(id), null));
                                httpSession.sendResponse(new Response(Response.ACCEPTED, EMPTY_BODY));
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
            );
        }
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

    private static void sendBadRequestResponse(HttpSession httpSession) throws IOException {
        httpSession.sendResponse(new Response(Response.BAD_REQUEST, EMPTY_BODY));
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
        config.closeSessions = true;
        return config;
    }

    private static HttpClient[] getHttpClients(List<String> urls, String thisUrl) {

        HttpClient[] clients = new HttpClient[urls.size()];
        int cnt = 0;
        List<String> tmpList = new ArrayList<>(urls);
        tmpList.sort(String::compareTo);
        for (String url : tmpList) {
            clients[cnt++] = url.equals(thisUrl) ? null : new HttpClient(new ConnectionString(url));
        }
        return clients;
    }

    private static MemorySegment keyFor(String id) {
        return MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] valueFor(Entry<MemorySegment> entry) {
        return entry.value().toArray(ValueLayout.JAVA_BYTE);
    }

}
