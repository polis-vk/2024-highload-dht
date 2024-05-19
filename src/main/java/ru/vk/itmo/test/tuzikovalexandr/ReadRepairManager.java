package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.http.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

public class ReadRepairManager {
    private final List<ResponseWithUrl> successResponses;
    private final String selfUrl;
    private final RequestHandler requestHandler;
    private final String paramId;
    private final HttpClient httpClient;

    public ReadRepairManager(List<ResponseWithUrl> successResponses, String selfUrl, RequestHandler requestHandler,
                             String paramId, HttpClient httpClient) {
        this.successResponses = successResponses;
        this.selfUrl = selfUrl;
        this.requestHandler = requestHandler;
        this.paramId = paramId;
        this.httpClient = httpClient;
    }

    public void execute() {
        if (checkReadRepair()) {
            List<String> nodesToUpdate = getNodesForUpdate();

            updateValues(nodesToUpdate);
        }
    }

    public boolean checkReadRepair() {
        long lastTimestamp = getTimestamp(successResponses.getLast()
                .getResponse().getHeader(Constants.NIO_TIMESTAMP_HEADER));
        long curTimestamp;

        for (ResponseWithUrl response : successResponses) {
            curTimestamp = getTimestamp(response.getResponse().getHeader(Constants.NIO_TIMESTAMP_HEADER));

            if (curTimestamp != lastTimestamp) {
                return true;
            }
        }

        return false;
    }

    public List<String> getNodesForUpdate() {
        List<String> nodes = new ArrayList<>();

        long lastTimestamp = getTimestamp(successResponses.getLast()
                .getResponse().getHeader(Constants.NIO_TIMESTAMP_HEADER));
        long curTimestamp;

        for (ResponseWithUrl responseWithUrl : successResponses) {
            curTimestamp = getTimestamp(responseWithUrl.getResponse().getHeader(Constants.NIO_TIMESTAMP_HEADER));

            if (curTimestamp != lastTimestamp) {
                nodes.add(responseWithUrl.getUrl());
            }
        }

        return nodes;
    }

    public void updateValues(List<String> nodesToUpdate) {
        byte[] body = successResponses.getLast().getResponse().getBody();

        for (String nodeUrl : nodesToUpdate) {
            if (selfUrl.equals(nodeUrl)) {
                requestHandler.putEntry(paramId, body);
            } else {
                HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(nodeUrl + "/v0/entity?id=" + paramId))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                        .setHeader(Constants.HTTP_TERMINATION_HEADER, "true")
                        .build();

                try {
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sendException(e);
                } catch (IOException e) {
                    sendException(e);
                }
            }
        }
    }

    private long getTimestamp(String strTimestamp) {
        return strTimestamp == null ? 0 : Long.parseLong(strTimestamp);
    }

    private void sendException(Exception exception) {
        String responseCode;
        if (exception.getClass().equals(TimeoutException.class)) {
            responseCode = Response.REQUEST_TIMEOUT;
        } else {
            responseCode = Response.INTERNAL_ERROR;
        }
        new Response(responseCode, Response.EMPTY);
    }
}
