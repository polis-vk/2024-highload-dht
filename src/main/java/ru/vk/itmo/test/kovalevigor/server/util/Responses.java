package ru.vk.itmo.test.kovalevigor.server.util;

import one.nio.http.Response;

public enum Responses {
    OK(Response.OK),
    NOT_FOUND(Response.NOT_FOUND),
    CREATED(Response.CREATED),
    ACCEPTED(Response.ACCEPTED),
    BAD_REQUEST(Response.BAD_REQUEST),
    NOT_ALLOWED(Response.METHOD_NOT_ALLOWED),
    INTERNAL_ERROR(Response.INTERNAL_ERROR),
    SERVICE_UNAVAILABLE(Response.SERVICE_UNAVAILABLE),
    NOT_ENOUGH_REPLICAS("504 Not Enough Replicas");

    private final String responseCode;

    Responses(String responseCode) {
        this.responseCode = responseCode;
    }

    public Response toResponse() {
        return emptyResponse(responseCode);
    }

    public String getResponseCode() {
        return responseCode;
    }

    private static Response emptyResponse(String resultCode) {
        return new Response(resultCode, Response.EMPTY);
    }
}
