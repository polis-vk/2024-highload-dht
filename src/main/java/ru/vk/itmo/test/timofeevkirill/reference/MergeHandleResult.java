package ru.vk.itmo.test.timofeevkirill.reference;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeHandleResult {
    private static final Logger log = LoggerFactory.getLogger(MergeHandleResult.class);
    private final Queue<CompletableFuture<HandleResult>> resultFutures;
    private final Queue<HandleResult> results;
    private final AtomicInteger count;
    private final int ack;
    private final int from;
    private final HttpSession session;

    public MergeHandleResult(HttpSession session, int size, int ack) {
        this.session = session;
        this.resultFutures = new ConcurrentLinkedQueue<>();
        this.results = new ConcurrentLinkedQueue<>();
        this.count = new AtomicInteger();
        this.ack = ack;
        this.from = size;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void add(CompletableFuture<HandleResult> resultFuture, ExecutorService executor) {
        resultFutures.add(resultFuture);
        resultFuture.whenCompleteAsync((result, e) -> {
            if (e == null) {
                results.add(result);
                int get = count.incrementAndGet();
                if (get == from) {
                    sendResult();
                    cancelRemainingFutures();
                }
            } else {
                log.error("Error remote handle result: " + e);
            }
        }, executor);
    }

    private void cancelRemainingFutures() {
        CompletableFuture<HandleResult> future;
        while ((future = resultFutures.poll()) != null) {
            future.cancel(true);
        }
    }

    private void sendResult() {
        HandleResult mergedResult = new HandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, null);

        int positiveCount = 0;
        for (HandleResult handleResult : results) {
            if (handleResult.status() == HttpURLConnection.HTTP_OK
                    || handleResult.status() == HttpURLConnection.HTTP_CREATED
                    || handleResult.status() == HttpURLConnection.HTTP_ACCEPTED
                    || handleResult.status() == HttpURLConnection.HTTP_NOT_FOUND) {
                positiveCount++;
                if (mergedResult.timestamp() <= handleResult.timestamp()) {
                    mergedResult = handleResult;
                }
            }
        }

        try {
            if (positiveCount < ack) {
                session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
            } else {
                session.sendResponse(new Response(String.valueOf(mergedResult.status()), mergedResult.data()));
            }
        } catch (Exception e) {
            ExceptionUtils.handleErrorFromHandleRequest(e, session);
        }

    }
}
