package ru.vk.itmo.test.kislovdanil.service.sharding;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ru.vk.itmo.test.kislovdanil.service.DatabaseHttpServer.sendResponse;
import static ru.vk.itmo.test.kislovdanil.service.sharding.BaseSharder.handleProxiedResponse;
import static ru.vk.itmo.test.kislovdanil.service.sharding.Sharder.NOT_ENOUGH_REPLICAS;
import static ru.vk.itmo.test.kislovdanil.service.sharding.Sharder.TIMESTAMP_HEADER;

// Thread-safe lock-free requests manager
public class RequestsManager {
    private final HttpSession session;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger checkedCount = new AtomicInteger(0);
    private final AtomicReference<Response> actualResponse = new AtomicReference<>();
    private final int ack;
    private final int method;
    private final int from;
    private final Iterable<CompletableFuture<Response>> futures;
    private final AtomicBoolean hasResponseSent = new AtomicBoolean(false);
    private static final Comparator<Response> timestampComparator = Comparator
            .comparingLong(RequestsManager::extractTimestampHeader).reversed();

    @SuppressWarnings("FutureReturnValueIgnored")
    public RequestsManager(Collection<CompletableFuture<Response>> futures, HttpSession session,
                           int ack, int method) {
        this.session = session;
        this.ack = ack;
        this.method = method;
        this.from = futures.size();
        this.futures = futures;
        for (CompletableFuture<Response> future : futures) {
            future.thenAccept(this::makeDecision);
        }
    }

    private static long extractTimestampHeader(Response response) {
        if (response == null) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(response.getHeader(TIMESTAMP_HEADER).substring(2));
    }

    private void makeMethodDecision(Response response,
                                    Set<Integer> validStatuses,
                                    boolean useTimestamps) {
        if (validStatuses.contains(response.getStatus())) {
            /* Update actual response in CAS loop. Even if we get ack responses before this one
            it's okay to send this response if it contains newer data */
            while (true) {
                Response currentActualResponse = actualResponse.get();
                if (!useTimestamps) {
                    // No difference which one would be set
                    actualResponse.set(response);
                    break;
                }
                if (timestampComparator.compare(response, currentActualResponse) < 0
                        || actualResponse.compareAndSet(currentActualResponse, response)) {
                    break;
                }
            }
            int currentSuccessCount = successCount.incrementAndGet();
            if (currentSuccessCount == ack) {
                final Response finalResponse = actualResponse.get();
                sendSingleResponse(handleProxiedResponse(finalResponse.getStatus(),
                        finalResponse.getBody(),
                        null));
                return;
            } else if (currentSuccessCount > ack) {
                return;
            }
        }
        if (checkedCount.incrementAndGet() >= from) {
            sendSingleResponse(new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY));
        }
    }

    private void sendSingleResponse(Response response) {
        if (hasResponseSent.compareAndSet(false, true)) {
            sendResponse(response, session);
            // No need to wait for all get requests
            if (method == Request.METHOD_GET) {
                for (CompletableFuture<Response> future : futures) {
                    future.cancel(true);
                }
            }
        }
    }

    public void makeDecision(Response response) {
        switch (method) {
            case Request.METHOD_GET -> makeMethodDecision(response, Set.of(200, 404), true);
            case Request.METHOD_PUT -> makeMethodDecision(response, Set.of(201), false);
            case Request.METHOD_DELETE -> makeMethodDecision(response, Set.of(202), false);
            default -> sendSingleResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

}
