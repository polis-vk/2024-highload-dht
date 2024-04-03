package ru.vk.itmo.test.kachmareugene;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.ServiceConfig;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

public class Coordinator {

    public int ack;
    public int from;

    public void getCoordinatorParams(Request request, ServiceConfig serviceConfig) {

        String tmpAck = request.getParameter("ack=");
        String tmpFrom = request.getParameter("from=");

        try {
            if (tmpFrom == null || tmpFrom.isEmpty()) {
                from = serviceConfig.clusterUrls().size();
            } else {
                from = Integer.parseInt(tmpFrom);
            }

            if (tmpAck == null || tmpAck.isEmpty()) {
                ack = (from + 1) / 2;
            } else {
                ack = Integer.parseInt(tmpAck);
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("strange numbers from and ack");
        }

        if (from < ack || from <= 0 || ack <= 0 || serviceConfig.clusterUrls().size() < from) {
            throw new IllegalArgumentException("strange numbers from and ack");
        }
    }

    public Response resolve(List<Response> responses, int method) {
        return switch (method) {
            case Request.METHOD_GET -> mergeGet(responses);

            case Request.METHOD_PUT -> majorityWins(responses,
                    new Response(Response.CREATED, Response.EMPTY));

            case Request.METHOD_DELETE -> majorityWins(responses,
                    new Response(Response.ACCEPTED, Response.EMPTY));

            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }

    private Response mergeGet(List<Response> responses) {
        Response maximalResponse = null;
        Timestamp maxTime = new Timestamp(0L);
        int countGood = 0;
        for (Response res : responses) {
            Timestamp time = new Timestamp(convertToLong(
                    Arrays.copyOfRange(res.getBody(), 0, Long.BYTES)));
            if (maxTime.before(time) || maxTime.equals(time)) {
                maxTime = time;
                maximalResponse = res;
            }
            if (res.getStatus() == 200 || res.getStatus() == 404) {
                countGood++;
            }
        }

        if (countGood < ack) {
            return new Response("504 Not Enough Replicas", Response.EMPTY);
        }
        byte[] clearedBody = Arrays.copyOfRange(maximalResponse.getBody(),
                Long.BYTES, maximalResponse.getBody().length);
        return new Response(maximalResponse.getHeaders()[0], clearedBody);
    }

    private static long convertToLong(byte[] bytes) {
        long value = 0L;
        for (byte b : bytes) {
            value = (value << 8) + (b & 255);
        }
        return value;
    }

    private Response majorityWins(List<Response> responses, Response response) {
        int responses2XX = 0;
        for (Response res : responses) {
            if (res.getStatus() >= 200 && res.getStatus() < 300) {
                responses2XX++;
            }
        }

        if (responses2XX >= ack) {
            return response;
        }
        return new Response("504 Not Enough Replicas", Response.EMPTY);
    }
}
