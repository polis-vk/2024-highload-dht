package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.http.HttpSession;
import one.nio.http.Response;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class MergeResultHandler {
    private final HttpSession session;
    private final AtomicInteger totalResponseCounter;
    private final AtomicInteger successCounter;

    private final ConcurrentSkipListSet<ResponseWrapper> results;

    private final AtomicBoolean closed = new AtomicBoolean(false);


    MergeResultHandler(Integer ack, Integer from, HttpSession session) {
        this.session = session;
        this.totalResponseCounter = new AtomicInteger(from);
        this.successCounter = new AtomicInteger(ack);
        this.results = new ConcurrentSkipListSet<>((r1, r2) -> Long.compare(r2.timestamp(), r1.timestamp()));
    }


    public synchronized void add(ResponseWrapper response) {
            if (closed.get()){
            return;
        }
        int total = this.totalResponseCounter.decrementAndGet();
        int success;
        if ((response.status() == HttpURLConnection.HTTP_OK
                || response.status() == HttpURLConnection.HTTP_CREATED
                || response.status() == HttpURLConnection.HTTP_ACCEPTED
                || response.status() == HttpURLConnection.HTTP_NOT_FOUND)) {
            success = successCounter.decrementAndGet();
            results.add(response);
            if (success == 0) {
                closed.set(true);
                sendResponse();
                return;
            }
        }
        if (total == 0) {
            closed.set(true);
            sendError();
        }

    }


    private void sendResponse() {
        try {
            ResponseWrapper response = results.getFirst();
            session.sendResponse(
                    new Response(
                            String.valueOf(response.status()),
                            response.data()
                    )
            );
        } catch (IOException e) {
            System.out.println(e);
            session.close();
        }
    }

    private void sendError() {
        try {
            session.sendResponse(new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
        } catch (IOException e) {
            System.out.println(e);
            session.close();
        }
    }
}