package ru.vk.itmo.test.khadyrovalmasgali.replication;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeHandleResult {

    private static final Logger log = LoggerFactory.getLogger(MergeHandleResult.class);
    private final AtomicInteger count;
    private final int from;
    private final int ack;
    private final HttpSession session;
    private final HandleResult[] handleResults;

    public MergeHandleResult(int from, int ack, HttpSession session) {
        handleResults = new HandleResult[from];
        this.count = new AtomicInteger();
        this.ack = ack;
        this.from = from;
        this.session = session;
    }

    public void add(HandleResult handleResult, int index) {
        handleResults[index] = handleResult;
        int get = count.incrementAndGet();

        if (get == from) {
            sendResult();
        }
    }


    private void sendResult() {
        HandleResult mergedResult = new HandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, null);

        int count = 0;
        for (HandleResult handleResult : handleResults) {
            if (handleResult.status() == HttpURLConnection.HTTP_OK
                    || handleResult.status() == HttpURLConnection.HTTP_CREATED
                    || handleResult.status() == HttpURLConnection.HTTP_ACCEPTED
                    || handleResult.status() == HttpURLConnection.HTTP_NOT_FOUND) {
                count++;
                if (mergedResult.timestamp() <= handleResult.timestamp()) {
                    mergedResult = handleResult;
                }
            }
        }

        try {
            if (count < ack) {
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
