package ru.vk.itmo.test.bandurinvladislav.util;

import one.nio.http.Request;
import one.nio.http.Response;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

public class NetworkUtil {

    private NetworkUtil() {
    }

    public static int getParameterAsInt(String parameter, int defaultValue) {
        return parameter == null ? defaultValue : Integer.parseInt(parameter);
    }

    public static boolean isMethodNotAllowed(Request request) {
        return switch (request.getMethod()) {
            case Request.METHOD_CONNECT, Request.METHOD_OPTIONS,
                    Request.METHOD_TRACE, Request.METHOD_HEAD,
                    Request.METHOD_POST -> true;
            default -> false;
        };
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public static Response successResponse(List<Response> responses) {
        return responses.stream()
                .max(Comparator.comparingLong(NetworkUtil::getTimestampHeader))
                .get();
    }

    public static Long getTimestampHeader(Response response) {
        String header = response.getHeader(Constants.HEADER_TIMESTAMP);
        return header == null ? -1 : Long.parseLong(header);
    }

    public static Response validateParams(String key, int ack, int from, int clusterSize) {
        if (StringUtil.isEmpty(key)) {
            return new Response(Response.BAD_REQUEST, "Id can't be empty".getBytes(StandardCharsets.UTF_8));
        }

        if (ack <= 0 || from <= 0) {
            return new Response(Response.BAD_REQUEST,
                    "ack and from can't be negative".getBytes(StandardCharsets.UTF_8));
        }

        if (from < ack) {
            return new Response(Response.BAD_REQUEST,
                    "from can't be less than ack".getBytes(StandardCharsets.UTF_8));
        }

        if (from > clusterSize) {
            return new Response(Response.BAD_REQUEST,
                    "from can't be greater than nodes count".getBytes(StandardCharsets.UTF_8));
        }

        return null;
    }
}
