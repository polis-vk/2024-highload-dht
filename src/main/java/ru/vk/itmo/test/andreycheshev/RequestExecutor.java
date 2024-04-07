package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RequestExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestExecutor.class);

    private static final int CPU_THREADS_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int MAX_CPU_THREADS_TIMES = 1;
    private static final int KEEPALIVE_MILLIS = 3000;
    private static final int MAX_WORK_QUEUE_SIZE = 300;

    private final ExecutorService executor;
    private final RequestHandler requestHandler;

    public RequestExecutor(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
        this.executor = new ThreadPoolExecutor(
                CPU_THREADS_COUNT,
                CPU_THREADS_COUNT * MAX_CPU_THREADS_TIMES,
                KEEPALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_WORK_QUEUE_SIZE),
                new WorkerThreadFactory("Distributor-thread"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void execute(Request request, HttpSession session) {
        try {
            executor.execute(() -> requestHandler.handle(request, session));
        } catch (RejectedExecutionException e) {
            LOGGER.error("Work queue overflow: task cannot be processed", e);
            requestHandler.sendAsync(HttpUtils.getTooManyRequests(), session);
        }
    }

    public void shutdown() throws IOException {
        // We use endless waiting.
        // This approach is appropriate, because if we meet with endless waiting,
        // the monitoring system will detect this fact and the node will be restarted.
        executor.close();
    }
}
