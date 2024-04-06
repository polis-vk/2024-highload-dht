package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;

import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ResponseCollector {
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";

    private final PriorityQueue<ResponseElements> collector = new PriorityQueue<>();

    private final HttpSession session;
    private final int method;
    private final int ack;
    private final int from;

    private final AtomicBoolean sendCondition = new AtomicBoolean(false);

    public ResponseCollector(HttpSession session, int method, int ack, int from) {
        this.session = session;
        this.method = method;
        this.ack = ack;
        this.from = from;
    }

    public boolean add(ResponseElements responseElements) {
        if (isResponseSucceeded(responseElements.getStatus())) {
            collector.add(responseElements);
        }

        return isReadySendResponse();
    }

    private static boolean isResponseSucceeded(int status) {
        return status == 200
                || status == 404
                || status == 410
                || status == 201
                || status == 202;
    }

    private boolean isReadySendResponse() {
        return ((method == Request.METHOD_GET && collector.size() == ack)
                || ack == from);
    }

    public void trySendResponse() {
        if (!sendCondition.compareAndSet(false, true)) {
            return;
        }

        if (isReadySendResponse()) {
            HttpUtils.sendResponse(new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY), session);
        } else {
            ResponseElements responseElements = collector.poll();
            HttpUtils.sendResponse(
                    HttpUtils.getOneNioResponse(method, responseElements),
                    session
            );
        }
    }
}
