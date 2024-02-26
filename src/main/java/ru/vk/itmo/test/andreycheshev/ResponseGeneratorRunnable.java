package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;

import static ru.vk.itmo.test.andreycheshev.RequestExecutor.TOO_MANY_REQUESTS;

public final class ResponseGeneratorRunnable implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ResponseGeneratorRunnable.class);
    private static final long MAX_AWAITING_TIME_MILLIS = 3000;
    private final Runnable runnableTask;
    private final long deadline;

    public ResponseGeneratorRunnable(Request request,
                                     HttpSession session,
                                     RequestHandler requestHandler) {
        this.deadline = System.currentTimeMillis();

        this.runnableTask = () -> {
            Response response;

            // Если дедлайн для выполнения задачи прошел
            if (System.currentTimeMillis() - deadline > MAX_AWAITING_TIME_MILLIS) {
                response = new Response(TOO_MANY_REQUESTS, Response.EMPTY);
            } else {
                response = requestHandler.handle(request);
            }

            try {
                session.sendResponse(response);
            } catch (IOException e) {
                logger.error("Error when sending a response to the client", e);
                throw new UncheckedIOException(e);
            }
        };
    }

    @Override
    public void run(){
        runnableTask.run();
    }
}
