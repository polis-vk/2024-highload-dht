package ru.vk.itmo.test.georgiidalbeev;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicInteger;

public class MyMergeHandleResult {
    private static final Logger log = LoggerFactory.getLogger(MyMergeHandleResult.class);
    private final AtomicInteger count;
    private final AtomicInteger success;
    private final int ack;
    private final int from;
    private final HttpSession session;
    private boolean isSent;
    MyHandleResult mergedResult = new MyHandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, null);

    public MyMergeHandleResult(HttpSession session, int size, int ack) {
        this.session = session;
        this.count = new AtomicInteger();
        this.ack = ack;
        this.from = size;
        this.success = new AtomicInteger();
    }

    public void add(MyHandleResult handleResult) {
        int get = count.incrementAndGet();

        if (handleResult.status() == HttpURLConnection.HTTP_OK
                || handleResult.status() == HttpURLConnection.HTTP_CREATED
                || handleResult.status() == HttpURLConnection.HTTP_ACCEPTED
                || handleResult.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            success.incrementAndGet();
            if (mergedResult.timestamp() <= handleResult.timestamp()) {
                mergedResult = handleResult;
            }
            if (success.get() >= ack && !isSent) {
                isSent = true;
                sendResult();
            }
        }

        if (get == from && success.get() < ack && !isSent) {
            sendResult();
        }
    }

    private void sendResult() {
        try {
            if (success.get() < ack) {
                session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
            } else {
                session.sendResponse(new Response(String.valueOf(mergedResult.status()), mergedResult.data()));
            }
        } catch (Exception e) {
            log.error("Exception during handleRequest", e);
            try {
                session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            } catch (IOException ex) {
                log.error("Exception while sending close connection", e);
                session.scheduleClose();
            }
        }

    }
}
