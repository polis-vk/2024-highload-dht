package ru.vk.itmo.test.reference;

import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeHandleResult {
    private static final Logger log = LoggerFactory.getLogger(MergeHandleResult.class);
    private final HandleResult[] handleResults;
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger count = new AtomicInteger();
    private final int ack;
    private final int from;
    private final ReferenceHttpSession session;

    public MergeHandleResult(ReferenceHttpSession session, int size, int ack) {
        this.session = session;
        this.handleResults = new HandleResult[size];
        this.ack = ack;
        this.from = size;
    }


    public void add(int index, HandleResult handleResult) {
        handleResults[index] = handleResult;
        if (handleResult.status() == HttpURLConnection.HTTP_OK
                || handleResult.status() == HttpURLConnection.HTTP_CREATED
                || handleResult.status() == HttpURLConnection.HTTP_ACCEPTED
                || handleResult.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            int get = successCount.incrementAndGet();
            if (get == ack) {
                sendResult();
                return;
            }
        }
        int currentCount = count.incrementAndGet();
        if (currentCount == from && successCount.get() < ack) {
            session.sendResponseOrClose(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
        }
    }


    private void sendResult() {
        HandleResult mergedResult = new HandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, null);
        for (HandleResult handleResult : handleResults) {
            if (handleResult != null) {
                if (mergedResult.timestamp() <= handleResult.timestamp()) {
                    mergedResult = handleResult;
                }
            }
        }

        session.sendResponseOrClose(new Response(String.valueOf(mergedResult.status()), mergedResult.data()));
    }
}
