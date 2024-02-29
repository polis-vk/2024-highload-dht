package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static one.nio.http.Response.INTERNAL_ERROR;

public class RequestExecutor {
    private static final Logger logger = LoggerFactory.getLogger(RequestExecutor.class);
    private static final int CPU_THREADS_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int KEEPALIVE_MILLIS = 3000;
    private static final long MAX_TASK_AWAITING_TIME_MILLIS = 3000;
    private static final int MAX_WORKERS_COUNT = 300;

    private final ExecutorService workers;
    private final RequestHandler requestHandler;

    static final String TOO_MANY_REQUESTS = "429 Too many requests";

    public RequestExecutor(RequestHandler requestHandler) throws IOException {
        this.requestHandler = requestHandler;

        this.workers = new ThreadPoolExecutor(
                CPU_THREADS_COUNT,
                CPU_THREADS_COUNT * 10,
                KEEPALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_WORKERS_COUNT),
                new WorkerThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void execute(Request request, HttpSession session) {
        long currTime = System.currentTimeMillis();

        try {
            workers.execute(() -> {
                Response response;

                // Если дедлайн для выполнения задачи прошел
                if (System.currentTimeMillis() - currTime > MAX_TASK_AWAITING_TIME_MILLIS) {
                    response = new Response(TOO_MANY_REQUESTS, Response.EMPTY);
                } else {
                    try {
                        response = requestHandler.handle(request);
                    } catch (Exception e) {
                        logger.error("Internal error of the DAO operation", e);
                        sendResponse(new Response(INTERNAL_ERROR, Response.EMPTY), session);
                        return;
                    }
                }

                sendResponse(response, session);
            });
        } catch (RejectedExecutionException e) { // Переполнение очереди
            sendResponse(
                    new Response(TOO_MANY_REQUESTS, Response.EMPTY),
                    session
            );
        }
    }

    private void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            logger.error("Error while sending a response to the client", e);
            throw new UncheckedIOException(e);
        }
    }

    public void shutdown() throws IOException {
        // Используем бесконенчое ожидание.
        // Такой подход уместен, поскольку, если произойдет зависание,
        // система мониторинга обнаружит этот факт и нода будет перезапущена.
        workers.close();
    }
}
