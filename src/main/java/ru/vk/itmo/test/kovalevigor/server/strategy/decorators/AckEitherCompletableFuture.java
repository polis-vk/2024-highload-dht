package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.Responses;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.GOOD_STATUSES;
import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.logIO;
import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.mergeResponses;

public class AckEitherCompletableFuture extends CompletableFuture<Response> {
    private final AtomicReference<Response> replicasResponse;
    private final AtomicInteger responseCount;
    private final AtomicInteger totalCount;
    private final int ack;

    public AckEitherCompletableFuture(int ack, int from) {
        this.ack = ack;
        replicasResponse = new AtomicReference<>();
        responseCount = new AtomicInteger();
        totalCount = new AtomicInteger(from);
    }

    public void markCompletedFuture(Response response, Throwable exception) {
        totalCount.decrementAndGet();
        if (response != null && GOOD_STATUSES.contains(response.getStatus())) {
            responseCount.incrementAndGet();
            Response oldResponse;
            Response merged;
            do {
                oldResponse = replicasResponse.get();
                merged = mergeResponses(oldResponse, response);
            } while (!replicasResponse.compareAndSet(oldResponse, merged));

            if (responseCount.get() >= ack) {
                complete(Objects.requireNonNullElseGet(merged, Responses.NOT_FOUND::toResponse));
                return;
            }
        } else if (response == null && exception instanceof IOException ioException) {
            logIO(ioException);
        }

        if (totalCount.get() == 0) {
            complete(null);
        }
    }
}
