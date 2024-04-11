package ru.vk.itmo.test.klimplyasov;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeHandleResult {
    private static final Logger log = LoggerFactory.getLogger(MergeHandleResult.class);
    private final HandleResult[] handleResults;
    private final AtomicInteger totalCount;
    private final AtomicInteger validCount;
    private final int ackThreshold;
    private final int totalExpected;
    private final HttpSession currentSession;

    public MergeHandleResult(HttpSession session, int size, int ackThreshold) {
        this.currentSession = session;
        this.handleResults = new HandleResult[size];
        this.totalCount = new AtomicInteger();
        this.validCount = new AtomicInteger();
        this.ackThreshold = ackThreshold;
        this.totalExpected = size;
    }

    public boolean add(int index, HandleResult handleResult) {
        handleResults[index] = handleResult;
        int valid = handleResult.status() == HttpURLConnection.HTTP_OK
                || handleResult.status() == HttpURLConnection.HTTP_CREATED
                || handleResult.status() == HttpURLConnection.HTTP_ACCEPTED
                || handleResult.status() == HttpURLConnection.HTTP_NOT_FOUND
                ? validCount.incrementAndGet() : validCount.get();
        if (valid >= ackThreshold || totalCount.incrementAndGet() == totalExpected) {
            sendResult();
            return true;
        }
        return false;
    }

    private void sendResult() {
        HandleResult mergedResult = new HandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, null);
        int validCount = 0;
        for (HandleResult handleResult : handleResults) {
            if (handleResult.status() == HttpURLConnection.HTTP_OK
                    || handleResult.status() == HttpURLConnection.HTTP_CREATED
                    || handleResult.status() == HttpURLConnection.HTTP_ACCEPTED
                    || handleResult.status() == HttpURLConnection.HTTP_NOT_FOUND) {
                validCount++;
                if (mergedResult.timestamp() <= handleResult.timestamp()) {
                    mergedResult = handleResult;
                }
            }
        }
        try {
            if (validCount < ackThreshold) {
                currentSession.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
            } else {
                currentSession.sendResponse(new Response(String.valueOf(mergedResult.status()), mergedResult.data()));
            }
        } catch (Exception e) {
            log.error("Exception during handleRequest", e);
            try {
                currentSession.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            } catch (IOException ex) {
                log.error("Exception while sending close connection", e);
                currentSession.scheduleClose();
            }
        }
    }
}
