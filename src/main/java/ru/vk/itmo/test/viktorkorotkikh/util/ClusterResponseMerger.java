package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.viktorkorotkikh.http.NodeResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ClusterResponseMerger {
    private static final Logger log = LoggerFactory.getLogger(ClusterResponseMerger.class);
    private final int ack;
    private final int from;
    private final Request originalRequest;
    private final HttpSession session;
    private final NodeResponse[] nodeResponses;
    private final AtomicInteger count;


    public ClusterResponseMerger(int ack, int from, Request originalRequest, HttpSession session) {
        this.ack = ack;
        this.from = from;
        this.originalRequest = originalRequest;
        this.session = session;
        this.nodeResponses = new NodeResponse[from];
        this.count = new AtomicInteger();
    }

    public void addToMerge(int index, NodeResponse response) {
        nodeResponses[index] = response;
        int newCount = count.incrementAndGet();
        if (newCount == from) {
            sendResponse();
        }
    }

    private void sendResponse() {
        Response response = LsmServerUtil.mergeReplicasResponses(originalRequest, nodeResponses, ack);
        sendResponseAndCloseSessionOnError(response);
    }

    private void sendResponseAndCloseSessionOnError(final Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException ex) {
            log.error("I/O error occurred when sending response");
            session.scheduleClose();
        }
    }
}
