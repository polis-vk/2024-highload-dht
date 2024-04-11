package ru.vk.itmo.test.osipovdaniil;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeHandleResult {
    private static final Logger log = LoggerFactory.getLogger(MergeHandleResult.class);
    private final HandleResult[] handleResults;
    private final AtomicInteger count;
    private final AtomicInteger countValid;
    private final int ack;
    private final int from;
    private final HttpSession session;

    public MergeHandleResult(HttpSession session, int size, int ack) {
        this.session = session;
        this.handleResults = new HandleResult[size];
        this.count = new AtomicInteger();
        this.countValid = new AtomicInteger();
        this.ack = ack;
        this.from = size;
    }


    public boolean add(int index, HandleResult handleResult) {
        handleResults[index] = handleResult;
        int valid = validateResultStatus(handleResult.status()) ? countValid.getAndIncrement() : countValid.get();
        if (valid >= ack) {
            sendResult();
            return true;
        }
        int get = count.incrementAndGet();
        if (get == from) {
            sendResult();
            return true;
        }
        return false;
    }

    private boolean validateResultStatus(final int status) {
        return status == HttpURLConnection.HTTP_OK
                ||status == HttpURLConnection.HTTP_CREATED
                || status == HttpURLConnection.HTTP_ACCEPTED
                || status == HttpURLConnection.HTTP_NOT_FOUND;
    }


    private void sendResult() {
        HandleResult mergedResult = new HandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, null);
        int count = 0;
        for (HandleResult handleResult : handleResults) {
            if (validateResultStatus(handleResult.status())) {
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
            Utils.sendEmptyInternal(session, log);
        }
    }
}
