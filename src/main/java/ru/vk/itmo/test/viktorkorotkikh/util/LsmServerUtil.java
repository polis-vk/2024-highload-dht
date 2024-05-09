package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.Request;
import one.nio.http.Response;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;

public class LsmServerUtil {
    public static final String TIMESTAMP_HEADER = "X-Entity-Timestamp";
    public static final String TIMESTAMP_HEADER_WITH_COLON = TIMESTAMP_HEADER + ':';

    private LsmServerUtil() {
    }

    public static String timestampToHeader(long timestamp) {
        return TIMESTAMP_HEADER + ": " + timestamp;
    }

    public static Response mergeReplicasResponses(
            final Request originalRequest,
            final AtomicReferenceArray<NodeResponse> responses,
            final int ack
    ) {
        switch (originalRequest.getMethod()) {
            case Request.METHOD_GET -> {
                return mergeGetResponses(originalRequest, responses, ack);
            }
            case Request.METHOD_PUT -> {
                return mergePutResponses(originalRequest, responses, ack);
            }
            case Request.METHOD_DELETE -> {
                return mergeDeleteResponses(originalRequest, responses, ack);
            }
            default -> throw new IllegalStateException("Unsupported method " + originalRequest.getMethod());
        }
    }

    private static Response mergeGetResponses(Request originalRequest, AtomicReferenceArray<NodeResponse> responses, int ack) {
        long maxTimestamp = -1;
        NodeResponse lastValue = null;
        int successfulResponses = 0;
        for (int i = 0; i < responses.length(); i++) {
            final NodeResponse response = responses.getOpaque(i);
            if (response == null) continue;
            final long valueTimestamp = getTimestamp(response);
            if (valueTimestamp > maxTimestamp) {
                maxTimestamp = valueTimestamp;
                lastValue = response;
            }
            if (response.statusCode() == HTTP_OK || response.statusCode() == HTTP_NOT_FOUND) {
                successfulResponses++;
            }
        }
        if (successfulResponses < ack) {
            return LSMConstantResponse.notEnoughReplicas(originalRequest);
        }
        if (lastValue == null) {
            lastValue = firstNotNull(responses);
        }
        return switch (lastValue.statusCode()) {
            case HTTP_OK -> Response.ok(lastValue.body());
            case HTTP_BAD_REQUEST -> LSMConstantResponse.badRequest(originalRequest);
            case HTTP_NOT_FOUND -> LSMConstantResponse.notFound(originalRequest);
            case HTTP_ENTITY_TOO_LARGE -> LSMConstantResponse.entityTooLarge(originalRequest);
            case 429 -> LSMConstantResponse.tooManyRequests(originalRequest);
            case HTTP_GATEWAY_TIMEOUT -> LSMConstantResponse.gatewayTimeout(originalRequest);
            default -> LSMConstantResponse.serviceUnavailable(originalRequest);
        };
    }

    private static Response mergePutResponses(
            Request originalRequest,
            AtomicReferenceArray<NodeResponse> responses,
            int ack
    ) {
        if (hasNotEnoughReplicas(responses, ack)) {
            return LSMConstantResponse.notEnoughReplicas(originalRequest);
        }
        return LSMConstantResponse.created(originalRequest);
    }

    private static Response mergeDeleteResponses(
            Request originalRequest,
            AtomicReferenceArray<NodeResponse> responses,
            int ack
    ) {
        if (hasNotEnoughReplicas(responses, ack)) {
            return LSMConstantResponse.notEnoughReplicas(originalRequest);
        }
        return LSMConstantResponse.accepted(originalRequest);
    }

    private static boolean hasNotEnoughReplicas(AtomicReferenceArray<NodeResponse> responses, int ack) {
        int successfulResponses = 0;
        for (int i = 0; i < responses.length(); i++) {
            final NodeResponse response = responses.getOpaque(i);
            if (response == null) continue;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                successfulResponses++;
            }
        }
        return successfulResponses < ack;
    }

    private static long getTimestamp(final NodeResponse response) {
        String timestamp = response.getHeader(TIMESTAMP_HEADER);
        if (timestamp == null) {
            return -1;
        }
        return Long.parseLong(timestamp);
    }

    private static NodeResponse firstNotNull(AtomicReferenceArray<NodeResponse> responses) {
        for (int i = 0; i < responses.length(); i++) {
            final NodeResponse response = responses.getOpaque(i);
            if (response != null) return response;
        }
        throw new NoSuchElementException();
    }
}
