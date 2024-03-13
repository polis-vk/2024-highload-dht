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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class DhtServer extends HttpServer {
    public static final byte[] EMPTY_BODY = new byte[0];
    public static final int THREADS_PER_PROCESSOR = 2;
    public static final long KEEP_ALIVE_TIME_MS = 1000;
    public static final int REQUEST_TIMEOUT_MS = 1024;
    public static final int MAX_RETRIES_TO_STOP_EXECUTOR = 256;
    protected final Map<Integer, HttpClient> hashToClientMapping;
    private final ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * THREADS_PER_PROCESSOR,
            Runtime.getRuntime().availableProcessors() * THREADS_PER_PROCESSOR,
            KEEP_ALIVE_TIME_MS,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>()
    );
    private final ReferenceDao dao;

    public DhtServer(ServiceConfig config) throws IOException {
        super(createConfig(config));
        hashToClientMapping = getHashToUrlMap(config.clusterUrls(), config.selfUrl());
        dao = new ReferenceDao(new Config(config.workingDir(), 1 << 24));
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            for (HttpClient httpClient : hashToClientMapping.values()) {
                if (httpClient != null && !httpClient.isClosed()) {
                    httpClient.close();
                }
            }
            threadPoolExecutor.shutdown();
            int cnt = 0;
            while (!threadPoolExecutor.awaitTermination(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Logger.getGlobal().warning("Awaiting for termination too long");
                if (++cnt >= MAX_RETRIES_TO_STOP_EXECUTOR) {
                    Logger.getGlobal().severe("Awaited to terminate for too long, throwing exception!");
                    throw new UncheckedTimeoutException("Awaited to terminate for too long, throwing exception!");
                }
            }
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @RequestMethod(METHOD_GET)
    @Path("/v0/entity")
    public void entity(@Param(value = "id") String id, HttpSession session, Request request) throws IOException {
        if (isKeyIncorrect(id)) {
            session.sendResponse(new Response(Response.BAD_REQUEST, EMPTY_BODY));
        } else {
            long start = System.currentTimeMillis();
            threadPoolExecutor.execute(
                    () -> {
                        try {
                            if (System.currentTimeMillis() - start >= REQUEST_TIMEOUT_MS) {
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
        return hashToClientMapping.get(Math.abs(key.hashCode()) % hashToClientMapping.size());
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
        if (isKeyIncorrect(id)) {
            httpSession.sendResponse(new Response(Response.BAD_REQUEST, EMPTY_BODY));
        } else {
            long start = System.currentTimeMillis();
            threadPoolExecutor.execute(
                    () -> {
                        try {
                            if (System.currentTimeMillis() - start > KEEP_ALIVE_TIME_MS) {
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
            httpSession.sendResponse(new Response(Response.BAD_REQUEST, EMPTY_BODY));
        } else {
            long start = System.currentTimeMillis();
            threadPoolExecutor.execute(
                    () -> {
                        try {
                            if (System.currentTimeMillis() - start > KEEP_ALIVE_TIME_MS) {
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
            session.sendResponse(new Response(Response.BAD_REQUEST, EMPTY_BODY));
        } else {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, EMPTY_BODY));
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
        config.closeSessions = true;
        return config;
    }

    private static Map<Integer, HttpClient> getHashToUrlMap(List<String> urls, String thisUrl) {

        Map<Integer, HttpClient> hashToUrlMapping = new HashMap<>();
        int cnt = 0;
        List<String> tmpList = new ArrayList<>(urls);
        tmpList.sort(String::compareTo);
        for (String url : tmpList) {
            hashToUrlMapping.put(cnt++, url.equals(thisUrl) ? null : new HttpClient(new ConnectionString(url)));
        }
        return hashToUrlMapping;
    }

    private static MemorySegment keyFor(String id) {
        return MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] valueFor(Entry<MemorySegment> entry) {
        return entry.value().toArray(ValueLayout.JAVA_BYTE);
    }

}
