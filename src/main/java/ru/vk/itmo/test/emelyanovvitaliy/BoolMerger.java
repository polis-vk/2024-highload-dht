package ru.vk.itmo.test.emelyanovvitaliy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class BoolMerger implements Merger<Boolean> {
    private final int from;
    private final int ack;
    private final AtomicInteger answeredOk;
    private final AtomicInteger answeredNotOk;
    private final CompletableFuture<Boolean> futureToComplete;

    public BoolMerger(int from, int ack) {
        this.from = from;
        this.ack = ack;
        answeredOk = new AtomicInteger(0);
        answeredNotOk = new AtomicInteger(0);
        futureToComplete = new CompletableFuture<>();
    }

    @Override
    public void acceptResult(Boolean success, Throwable throwable) {
        if (success) {
            if (answeredOk.incrementAndGet() == ack) {
                futureToComplete.complete(true);
            }
        } else {
            if (answeredNotOk.incrementAndGet() == from - ack + 1) {
                futureToComplete.complete(false);
            }
        }
    }

    @Override
    public CompletableFuture<Boolean> getCompletableFuture() {
        return futureToComplete;
    }
}
