package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.http.Request;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Comparator;
import java.util.List;

public final class Utils {

    private Utils() {
    }

    public static void sortResponses(List<ResponseWithUrl> successResponses) {
        successResponses.sort(Comparator.comparingLong(r -> {
            String timestamp = r.getResponse().getHeader(Constants.NIO_TIMESTAMP_HEADER);
            return timestamp == null ? 0 : Long.parseLong(timestamp);
        }));
    }

    public static HttpRequest createProxyRequest(Request request, String nodeUrl, String params) {
        return HttpRequest.newBuilder(URI.create(nodeUrl + "/v0/entity?id=" + params))
                .method(request.getMethodName(), request.getBody() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .setHeader(Constants.HTTP_TERMINATION_HEADER, "true")
                .build();
    }
}
