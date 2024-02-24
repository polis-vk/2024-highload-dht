package ru.vk.itmo.test.andreycheshev;

import one.nio.http.Request;
import one.nio.http.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Callable;

import static one.nio.http.Response.SERVICE_UNAVAILABLE;

public final class ResponseGeneratorCallable implements Callable<Response> {
    private static final long MAX_TIME_DELTA_MILLIS = 3000;
    private final Callable<Response> callable;
    private final long deadline;

    public ResponseGeneratorCallable(Request request,
                RequestHandler requestHandler) {
        this.deadline = System.currentTimeMillis();

        callable = () -> {
            // If the task is overdue
            if (System.currentTimeMillis() - deadline > MAX_TIME_DELTA_MILLIS) {
                return new Response(SERVICE_UNAVAILABLE, Response.EMPTY);
            } else {
                try {
                    return requestHandler.handle(request);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }

    @Override
    public Response call() throws Exception {
        return callable.call();
    }
}
