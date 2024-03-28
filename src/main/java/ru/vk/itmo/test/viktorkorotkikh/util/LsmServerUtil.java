package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMConstantResponse;

import java.net.http.HttpResponse;
import java.util.List;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;

public class LsmServerUtil {
    public static final String TIMESTAMP_HEADER = "X-Entity-Timestamp";

    private LsmServerUtil() {
    }

    public static String timestampToHeader(long timestamp) {
        return TIMESTAMP_HEADER + ": " + timestamp;
    }

    public static Response mergeReplicasResponses(
            final Request originalRequest,
            final List<HttpResponse<byte[]>> responses,
            final int ack
    ) {
        switch (originalRequest.getMethod()) {
            case Request.METHOD_GET -> {
                long maxTimestamp = -1;
                HttpResponse<byte[]> lastValue = null;
                for (HttpResponse<byte[]> response : responses) {
                    final long valueTimestamp = getTimestamp(response);
                    if (valueTimestamp > maxTimestamp) {
                        maxTimestamp = valueTimestamp;
                        lastValue = response;
                    }
                }
                if (lastValue == null) {
                    lastValue = responses.getFirst();
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
            case Request.METHOD_PUT -> {
                if (hasNotEnoughReplicas(responses, ack))
                    return LSMConstantResponse.notEnoughReplicas(originalRequest);
                return LSMConstantResponse.created(originalRequest);
            }
            case Request.METHOD_DELETE -> {
                if (hasNotEnoughReplicas(responses, ack))
                    return LSMConstantResponse.notEnoughReplicas(originalRequest);
                return LSMConstantResponse.accepted(originalRequest);
            }
            default -> throw new IllegalStateException("Unsupported method " + originalRequest.getMethod());
        }
    }

    private static boolean hasNotEnoughReplicas(List<HttpResponse<byte[]>> responses, int ack) {
        int successfulResponses = 0;
        for (HttpResponse<byte[]> response : responses) {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                successfulResponses++;
            }
        }
        return successfulResponses < ack;
    }

    private static long getTimestamp(final HttpResponse<byte[]> response) {
        List<String> timestamps = response.headers().allValues(TIMESTAMP_HEADER);
        if (timestamps.isEmpty()) {
            return -1;
        }
        return Long.parseLong(timestamps.getFirst());
    }
}
