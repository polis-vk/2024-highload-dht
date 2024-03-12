package ru.vk.itmo.test.timofeevkirill;

import one.nio.async.CustomThreadFactory;
import one.nio.http.Request;

import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Settings {
    public static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1мб
    public static final int MAX_QUEUE_SIZE = 8192;
    public static final String VERSION_PREFIX = "/v0";
    public static final Set<Integer> SUPPORTED_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );
    public static final long MAX_PROCESSING_TIME_FOR_REQUEST = TimeUnit.SECONDS.toNanos(2);

    public static ThreadPoolExecutor getDefaultThreadPoolExecutor() {
        return new ThreadPoolExecutor(
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().availableProcessors(),
                10L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
                new CustomThreadFactory("WorkerThread", true),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private Settings() {
    }
}
