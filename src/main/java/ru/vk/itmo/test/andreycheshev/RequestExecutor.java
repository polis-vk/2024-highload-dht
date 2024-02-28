package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.andreycheshev.dao.PersistentReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RequestExecutor {
    private static final int CPU_THREADS_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int KEEPALIVE_MILLIS = 3000;
    private static final int BLOCKING_QUEUE_MAX_SIZE = 500;
    private static final Logger logger = LoggerFactory.getLogger(RequestExecutor.class);

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService workers;
    private final RequestHandler requestHandler;

    static final String TOO_MANY_REQUESTS = "429 Too many requests";

    public RequestExecutor(Config daoConfig) throws IOException {
        this.dao = new PersistentReferenceDao(daoConfig);

        this.requestHandler = new RequestHandler(this.dao);

        this.workers = new ThreadPoolExecutor(
                CPU_THREADS_COUNT,
                CPU_THREADS_COUNT * 10,
                KEEPALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BLOCKING_QUEUE_MAX_SIZE),
                new WorkerThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void execute(Request request, HttpSession session) {
        Runnable task = new ResponseGeneratorRunnable(request, session, requestHandler);

        try {
            workers.execute(task);
        }
        // Переполнение очереди, невозможно взять новую задачу в исполнение
        catch (RejectedExecutionException e) {
            logger.error("The queue is full, the task has not been processed", e);
            try {
                session.sendResponse(
                        new Response(TOO_MANY_REQUESTS, Response.EMPTY)
                );
            } catch (IOException ex) {
                logger.error("Error when sending a response to the client", ex);
                throw new UncheckedIOException(ex);
            }
        }
    }

    public void shutdown() throws IOException {
        // Используем бесконенчое ожидание.
        // Такой подход уместен, поскольку, если произойдет зависание,
        // система мониторинга обнаружит этот факт и нода будет перезапущена.
        workers.close();

        dao.close();
    }
}
