package ru.vk.itmo.test.kislovdanil.service.sharding;

import one.nio.http.Request;
import one.nio.http.Response;

import java.net.http.HttpClient;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public abstract class ReplicativeBaseSharder extends BaseSharder {
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";

    private static final Comparator<Response> timestampComparator = new Comparator<>() {

        private long extractTimestampHeader(Response response) {
            return Long.parseLong(response.getHeader(TIMESTAMP_HEADER).substring(2));
        }

        @Override
        public int compare(Response o1, Response o2) {
            return -Long.compare(extractTimestampHeader(o1), extractTimestampHeader(o2));
        }
    };

    protected ReplicativeBaseSharder(HttpClient client) {
        super(client);
    }

    private boolean isSuccessful(List<Response> responses, int acknowledge, Set<Integer> validStatuses) {
        int approved = 0;
        for (Response response : responses) {
            if (validStatuses.contains(response.getStatus())) {
                approved++;
            }
            if (approved >= acknowledge) {
                return true;
            }
        }
        return false;
    }

    private Response chooseResponse(List<Response> responses, Set<Integer> validStatuses, boolean useTimestamps) {
        Stream<Response> validResponses = responses.stream()
                .filter(response -> validStatuses.contains(response.getStatus()));
        Response errorResponse = new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        return useTimestamps ? validResponses
                .max(timestampComparator).orElse(errorResponse) :
                validResponses.findAny().orElse(errorResponse);
    }

    private Response makeMethodDecision(List<Response> responses,
                                        int acknowledge, Set<Integer> validStatuses,
                                        boolean useTimestamps) {
        if (isSuccessful(responses, acknowledge, validStatuses)) {
            return chooseResponse(responses, validStatuses, useTimestamps);
        }
        return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
    }

    @Override
    public Response makeDecision(List<Response> responses, int acknowledge, int method) {
        return switch (method) {
            case Request.METHOD_GET -> makeMethodDecision(responses, acknowledge, Set.of(200, 404), true);
            case Request.METHOD_PUT -> makeMethodDecision(responses, acknowledge, Set.of(201), false);
            case Request.METHOD_DELETE -> makeMethodDecision(responses, acknowledge, Set.of(202), false);
            default -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        };
    }
}
