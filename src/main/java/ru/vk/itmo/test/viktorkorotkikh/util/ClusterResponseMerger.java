package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicInteger;

public class ClusterResponseMerger {
    private static final Logger log = LoggerFactory.getLogger(ClusterResponseMerger.class);
    private final int ack;
    private final int allowedUnsuccessfulResponses;
    private final Request originalRequest;
    private final HttpSession session;
    private final NodeResponse[] nodeResponses;
    private final AtomicInteger unsuccessfulResponsesCount;
    private final AtomicInteger successfulResponsesCount;

    public ClusterResponseMerger(int ack, int from, Request originalRequest, HttpSession session) {
        this.ack = ack;
        this.allowedUnsuccessfulResponses = from - ack;
        this.originalRequest = originalRequest;
        this.session = session;
        this.nodeResponses = new NodeResponse[from];
        this.unsuccessfulResponsesCount = new AtomicInteger();
        this.successfulResponsesCount = new AtomicInteger();
    }

    public void addToMerge(int index, NodeResponse response) {
        nodeResponses[index] = response;
        if (isSuccessfulResponse(response.statusCode())) {
            int newSuccessfulResponsesCount = successfulResponsesCount.incrementAndGet();
            if (newSuccessfulResponsesCount == ack) {
                sendResponse();
            }
            return;
        }
        int newUnsuccessfulResponsesCount = unsuccessfulResponsesCount.incrementAndGet();
        if (newUnsuccessfulResponsesCount > allowedUnsuccessfulResponses && successfulResponsesCount.get() < ack) {
            sendResponseAndCloseSessionOnError(LSMConstantResponse.notEnoughReplicas(originalRequest));
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

    private static boolean isSuccessfulResponse(int responseStatusCode) {
        return responseStatusCode == HttpURLConnection.HTTP_OK
                || responseStatusCode == HttpURLConnection.HTTP_CREATED
                || responseStatusCode == HttpURLConnection.HTTP_ACCEPTED
                || responseStatusCode == HttpURLConnection.HTTP_NOT_FOUND;
    }
}
