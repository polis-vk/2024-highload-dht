package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.Request;

public enum Parameters {
    ID("id");

    private final String name;


    Parameters(String name) {
        this.name = name + "=";
    }

    public static String getParameter(Request request, Parameters parameter) {
        return request.getParameter(parameter.name);
    }
}
