package ru.vk.itmo.test.kovalevigor.server.strategy.util;

import one.nio.http.Request;
import one.nio.http.Response;

public enum Headers {

    TIMESTAMP("X-Timestamp"),
    REPLICATION("REPLICATION");
    // Наверное,
    // для какой-то безопастности стоит набабахать проверок,
    // но я верю, что ноды кластера между собой общаются по выделенному каналу, а не по http ;)

    private final String name;

    Headers(String name) {
        this.name = name;
    }

    public static String getHeader(Response response, Headers header) {
        return response.getHeader(header.getOneNioName());
    }

    public static String getHeader(Request request, Headers header) {
        return request.getHeader(header.getOneNioName());
    }

    public static boolean hasHeader(Request request, Headers header) {
        return getHeader(request, header) != null;
    }

    public static void addHeader(Response response, Headers header, Object value) {
        response.addHeader(header.getOneNioName() + value.toString());
    }

    public static void addHeader(Request request, Headers header, Object value) {
        request.addHeader(header.getOneNioName() + value.toString());
    }

    public String getName() {
        return this.name;
    }

    private String getOneNioName() {
        return this.name + ":";
    }
}
