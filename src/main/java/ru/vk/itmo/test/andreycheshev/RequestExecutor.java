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
    private static final String TOO_MANY_REQUESTS = "429 Too many requests";
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestExecutor.class);
    private static final int CPU_THREADS_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int MAX_CPU_THREADS_TIMES = 1;
    private static final int KEEPALIVE_MILLIS = 3000;
    private static final long MAX_TASK_AWAITING_TIME_MILLIS = 3000;
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
                new WorkerThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void execute(Request request, HttpSession session) {
        long currTime = System.currentTimeMillis();

        try {
            executor.execute(() -> {
                Response response;

                // If the deadline for completing the task has passed.
                if (System.currentTimeMillis() - currTime > MAX_TASK_AWAITING_TIME_MILLIS) {
                    LOGGER.error("The server is overloaded with too many requests");
                    response = new Response(TOO_MANY_REQUESTS, Response.EMPTY);
                } else {
                    try {
                        response = requestHandler.handle(request);
                    } catch (Exception e) {
                        LOGGER.error("Internal error of the DAO operation", e);
                        response = new Response(INTERNAL_ERROR, Response.EMPTY);
                    }
                }

                sendResponse(response, session);
            });
        } catch (RejectedExecutionException e) { // Queue overflow.
            LOGGER.error("Work queue overflow: task cannot be processed", e);
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
            LOGGER.error("Error when sending a response to the client", e);
            throw new UncheckedIOException(e);
        }
    }

    public void shutdown() throws IOException {
        // We use endless waiting.
        // This approach is appropriate, because if we meet with endless waiting,
        // the monitoring system will detect this fact and the node will be restarted.
        executor.close();
    }
}
