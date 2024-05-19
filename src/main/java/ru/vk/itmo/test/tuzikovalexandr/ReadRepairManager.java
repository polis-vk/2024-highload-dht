package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.http.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class ReadRepairManager {
    public ReadRepairManager() {

    }

    public boolean checkReadRepair(List<Response> sortedResponses) {
        long lastTimestamp = getTimestamp(sortedResponses.getLast().getHeader(Constants.NIO_TIMESTAMP_HEADER));
        long curTimestamp;

        for (Response response : sortedResponses) {
            curTimestamp = getTimestamp(response.getHeader(Constants.NIO_TIMESTAMP_HEADER));

            if (curTimestamp != lastTimestamp) {
                return true;
            }
        }

        return false;
    }

    public List<String> getNodesForUpdate(Map<String, Response> successResponses, Response response) {
        Iterator<Map.Entry<String, Response>> responses = successResponses.entrySet().iterator();
        List<String> nodes = new ArrayList<>();

        long lastTimestamp = getTimestamp(response.getHeader(Constants.NIO_TIMESTAMP_HEADER));
        long curTimestamp;

        while (responses.hasNext()) {
            Map.Entry<String, Response> curResponse = responses.next();
            curTimestamp = getTimestamp(curResponse.getValue().getHeader(Constants.NIO_TIMESTAMP_HEADER));

            if (curTimestamp != lastTimestamp) {
                nodes.add(curResponse.getKey());
            }
        }

        return nodes;
    }

    public void updateValues(String selfUrl, RequestHandler requestHandler, List<String> nodesToUpdate,
                              Response lastValue, String paramId, HttpClient httpClient) {
        byte[] body = lastValue.getBody();

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
                    throw new RuntimeException(e);
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
