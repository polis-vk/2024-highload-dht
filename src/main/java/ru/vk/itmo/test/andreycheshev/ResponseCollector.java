package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ResponseCollector {
    private final PriorityBlockingQueue<ResponseElements> collector; // Contains only successful responses.

    private final int method;
    private final int ack;
    private final int from;
    private final HttpSession session;

    private final AtomicBoolean sendCondition = new AtomicBoolean(false);
    private final AtomicInteger responseCount = new AtomicInteger(0);

    public ResponseCollector(int method, int ack, int from, HttpSession session) {
        this.collector = new PriorityBlockingQueue<>(from);

        this.method = method;
        this.ack = ack;
        this.from = from;
        this.session = session;
    }

    public HttpSession getSession() {
        return session;
    }

    public boolean incrementResponsesCounter() {
        return responseCount.incrementAndGet() == from;
    }

    public boolean add(ResponseElements responseElements) {
        responseCount.incrementAndGet();

        if (isResponseSucceeded(responseElements.getStatus())) {
            collector.put(responseElements);
        }

        return isReadySendResponse();
    }

    private static boolean isResponseSucceeded(int status) {
        return status == 404 || status == 410
                || status == 200 || status == 201 || status == 202;
    }

    private boolean isReadySendResponse() {
        return (method == Request.METHOD_GET && collector.size() >= ack) || responseCount.get() >= from;
    }

    public void sendResponse() {
        if (!sendCondition.compareAndSet(false, true)) {
            return;
        }

        // The following actions are performed only once.

        if (collector.size() >= ack) {
            HttpUtils.sendResponse(HttpUtils.getOneNioResponse(method, collector.poll()), session);
        } else {
            HttpUtils.sendResponse(HttpUtils.getNotEnoughReplicas(), session);
        }
    }
}
