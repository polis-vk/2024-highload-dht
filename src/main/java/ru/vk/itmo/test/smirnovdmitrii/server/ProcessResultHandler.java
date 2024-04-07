package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ProcessResultHandler {
    private static final Logger logger = LoggerFactory.getLogger(ProcessResultHandler.class);
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    private final AtomicInteger successes = new AtomicInteger(0);
    private final AtomicInteger fails = new AtomicInteger(0);
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private final AtomicReference<ProcessResult> outcome = new AtomicReference<>(null);
    private final HttpSession session;
    private final int method;
    private final int ack;
    private final int from;

    public ProcessResultHandler(
            final HttpSession session,
            final int method,
            final int ack,
            final int from
    ) {
        this.session = session;
        this.method = method;
        this.ack = ack;
        this.from = from;
    }

    public ProcessResultHandler(
            final HttpSession session,
            final int method
    ) {
        this(session, method, 1, 1);
    }

    public int method() {
        return method;
    }

    public int ack() {
        return ack;
    }

    public int from() {
        return from;
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    public void add(final ProcessResult result) {
        add(result, null);
    }

    public void add(final ProcessResult result, final Throwable throwable) {
        if (throwable != null) {
            logger.error("Exception while processing ack's", throwable);
        }
        if (isClosed.get()) {
            return;
        }

        Response response;
        if (throwable == null && success(result)) {
            if (successes.incrementAndGet() >= ack) {
                response = new Response(String.valueOf(result.status()), result.data());
            } else {
                return;
            }
        } else {
            if (fails.incrementAndGet() > from - ack) {
                response = new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
            } else {
                return;
            }
        }
        sendResponse(response);
    }

    private boolean success(final ProcessResult result) {
        final int status = result.status();
        if (this.method == Request.METHOD_GET) {
            final boolean isSuccess = status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_NOT_FOUND;
            while (true) {
                final ProcessResult curResult = outcome.get();
                if (curResult != null && curResult.timestamp() > result.timestamp()) {
                    break;
                }
                if (outcome.compareAndSet(curResult, result)) {
                    break;
                }
            }
            return isSuccess;
        } else {
            return status == HttpURLConnection.HTTP_CREATED || status == HttpURLConnection.HTTP_ACCEPTED;
        }
    }

    public void sendResponse(final Response response) {
        if (isClosed.compareAndSet(false, true)) {
            try {
                session.sendResponse(response);
            } catch (final IOException e) {
                logger.error("Error while sending response", e);
                try {
                    session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                } catch (final IOException ignored) {
                    // do nothing
                }
            }
        }
    }

    public void sendResult(final ProcessResult result) {
        final Response response = new Response(String.valueOf(result.status()), result.data());
        response.addHeader(Utils.TIMESTAMP_HEADER_NAME + ":" + result.timestamp());
        sendResponse(response);
    }
}
