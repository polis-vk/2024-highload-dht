package ru.vk.itmo.test.kovalevigor.server.util;

import one.nio.http.Request;

import java.util.function.Function;

public enum Parameters {
    ID("id"),
    ACK("ack"),
    FROM("from"),
    START("start"),
    END("end");

    private final String name;

    Parameters(String name) {
        this.name = name + "=";
    }

    public static <T> T getParameter(
            Request request,
            Parameters parameter,
            Function<String, T> conv,
            T defaultValue
    ) {
        String value = request.getParameter(parameter.name);
        if (value == null) {
            return defaultValue;
        }
        return conv.apply(request.getParameter(parameter.name));
    }

    public static <T> T getParameter(
            Request request,
            Parameters parameter,
            Function<String, T> conv
    ) {
        return getParameter(request, parameter, conv, null);
    }

    public static String getParameter(Request request, Parameters parameter) {
        return getParameter(request, parameter, Function.identity());
    }
}
