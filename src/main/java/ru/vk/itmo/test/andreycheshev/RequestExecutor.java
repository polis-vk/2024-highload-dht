package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.andreycheshev.dao.PersistentReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RequestExecutor {
    private static final int KEEPALIVE_MILLIS = 3000;
    private static final int MAX_WORKERS_COUNT = 100;
    private static final int CPU_THREADS_COUNT = Runtime.getRuntime().availableProcessors();
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService workers;
    private final RequestHandler requestHandler;

    public RequestExecutor(Config daoConfig) throws IOException {
        this.dao = new PersistentReferenceDao(daoConfig);

        this.requestHandler = new RequestHandler(this.dao);

        this.workers = new ThreadPoolExecutor(
                CPU_THREADS_COUNT,
                CPU_THREADS_COUNT * 10,
                KEEPALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_WORKERS_COUNT),
                new WorkerThreadFactory(),
                new WorkerThreadExceptionHandler()
        );
    }

    public void execute(Request request, HttpSession session) {
        Runnable task = new ResponseGeneratorRunnable(request, session, requestHandler);
        workers.execute(task);
    }

    public void shutdown() throws IOException {
        workers.shutdown();
        dao.close();
    }
}
