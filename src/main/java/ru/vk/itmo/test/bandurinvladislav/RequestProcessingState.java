package ru.vk.itmo.test.bandurinvladislav;

import one.nio.http.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class RequestProcessingState {
    final List<Response> responses;
    final AtomicBoolean responseSent = new AtomicBoolean(false);
    final AtomicLong failedResponseCount = new AtomicLong(0);

    public RequestProcessingState(int capacity) {
        this.responses = Collections.synchronizedList(new ArrayList<>(capacity));
    }

    public List<Response> getResponses() {
        return responses;
    }

    public AtomicBoolean isResponseSent() {
        return responseSent;
    }

    public AtomicLong getFailedResponseCount() {
        return failedResponseCount;
    }
}