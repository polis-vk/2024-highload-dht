package ru.vk.itmo.test.reference;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeHandleResult {
    private static final Logger log = LoggerFactory.getLogger(MergeHandleResult.class);
    private final Object[] handleResults;
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger count = new AtomicInteger();
    private final int ack;
    private final int from;
    private final ReferenceHttpSession session;
    private final ExecutorService executorMerge;

    public MergeHandleResult(ReferenceHttpSession session, ExecutorService executorMerge, int size, int ack) {
        this.session = session;
        this.handleResults = new Object[size];
        this.ack = ack;
        this.from = size;
        this.executorMerge = executorMerge;
    }


    public void add(int index, CompletableFuture<HandleResult> handleResult) {
        handleResults[index] = handleResult;

        merge(handleResult);
    }

    @CanIgnoreReturnValue
    private CompletableFuture<HandleResult> merge(CompletableFuture<HandleResult> handleResult) {
        return handleResult.whenCompleteAsync((handleResult1, throwable) -> {
            if (throwable != null) {
                if (throwable instanceof IOException e) {
                    log.info("I/O exception", e);
                } else if (throwable instanceof Exception e) {
                    log.info("exception", e);
                } else {
                    // todo error
                }
            }
            if (handleResult1 != null) {
                if (handleResult1.status() == HttpURLConnection.HTTP_OK
                        || handleResult1.status() == HttpURLConnection.HTTP_CREATED
                        || handleResult1.status() == HttpURLConnection.HTTP_ACCEPTED
                        || handleResult1.status() == HttpURLConnection.HTTP_NOT_FOUND) {
                    int get = successCount.incrementAndGet();
                    if (get == ack) {
                        try {
                            sendResult();
                        } catch (Exception e) {
                            // todo
                            session.sendResponseOrClose(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
                        }
                        return;
                    }
                }
            }
            int currentCount = count.incrementAndGet();
            if (currentCount == from && successCount.get() < ack) {
                session.sendResponseOrClose(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
            }
        }, executorMerge);
    }


    private void sendResult() throws ExecutionException, InterruptedException {
        HandleResult mergedResult = new HandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, null);
        for (Object o : handleResults) {
            CompletableFuture<HandleResult> future = (CompletableFuture<HandleResult>) o;
            if (future == null || !future.isDone()) {
                continue;
            }
            HandleResult handleResult = future.get();
            if (handleResult != null) {
                if (mergedResult.timestamp() <= handleResult.timestamp()) {
                    mergedResult = handleResult;
                }
            }
        }

        session.sendResponseOrClose(new Response(String.valueOf(mergedResult.status()), mergedResult.data()));
    }
}
