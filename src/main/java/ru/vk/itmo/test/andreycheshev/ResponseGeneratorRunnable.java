package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;

import java.io.IOException;
import java.io.UncheckedIOException;

import static one.nio.http.Response.SERVICE_UNAVAILABLE;

public final class ResponseGeneratorRunnable implements Runnable {
    private static final long MAX_AWAITING_IME_MILLIS = 3000;
    private final Runnable runnable;
    private final long deadline;

    public ResponseGeneratorRunnable(Request request,
                                     HttpSession session,
                                     RequestHandler requestHandler) {
        this.deadline = System.currentTimeMillis();

        runnable = () -> {
            // If the task is overdue
            Response response;
            if (System.currentTimeMillis() - deadline > MAX_AWAITING_IME_MILLIS) {
                response = new Response(SERVICE_UNAVAILABLE, Response.EMPTY);
            } else {
                try {
                    response = requestHandler.handle(request);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            try {
                session.sendResponse(response);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    @Override
    public void run(){
        runnable.run();
    }
}
