package ru.vk.itmo.test.emelyanovvitaliy;

import java.util.concurrent.CompletableFuture;

public interface Merger<T> {

    void acceptResult(T result, Throwable exception);

    CompletableFuture<T> getCompletableFuture();

}
