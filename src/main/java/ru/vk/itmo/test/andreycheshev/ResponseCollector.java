package ru.vk.itmo.test.andreycheshev;

import one.nio.http.Request;
import one.nio.http.Response;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ResponseCollector {
    private final PriorityBlockingQueue<ResponseElements> collector; // Contains only successful responses.

    private final int method;
    private final int ack;
    private final int from;
    private final AtomicBoolean sendCondition = new AtomicBoolean(false);
    private final AtomicInteger responseCount = new AtomicInteger(0);

    public ResponseCollector(int method, int ack, int from) {
        this.collector = new PriorityBlockingQueue<>(from);

        this.method = method;
        this.ack = ack;
        this.from = from;
    }

    public boolean incrementResponsesCounter() {
        responseCount.incrementAndGet();

        if (isReadySendResponse()) {
            // Setting the flag to ensure that only one response is sent.
            return sendCondition.compareAndSet(false, true);
        }
        return false;
    }

    public void add(ResponseElements responseElements) {
        if (isResponseSucceeded(responseElements.getStatus())) {
            collector.put(responseElements);
        }
    }

    private static boolean isResponseSucceeded(int status) {
        return HttpUtils.AVAILABLE_RESPONSE_CODES.containsKey(status);
    }

    private boolean isReadySendResponse() {
        return (method == Request.METHOD_GET && collector.size() >= ack) || responseCount.get() >= from;
    }

    public Response getResponse() {
        return collector.size() >= ack
                ? HttpUtils.getOneNioResponse(method, collector.poll())
                : HttpUtils.getNotEnoughReplicas();
    }
}