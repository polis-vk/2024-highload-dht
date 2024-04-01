package ru.vk.itmo.test.alexeyshemetov;

import one.nio.http.Response;

public final class Utils {
    private Utils() {
    }

    public static String statusCodeToResponseCode(int statusCode) {
        return switch (statusCode) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 203 -> Response.NON_AUTHORITATIVE_INFORMATION;
            case 204 -> Response.NO_CONTENT;
            case 205 -> Response.RESET_CONTENT;
            case 206 -> Response.PARTIAL_CONTENT;
            case 400 -> Response.BAD_REQUEST;
            case 401 -> Response.UNAUTHORIZED;
            case 402 -> Response.PAYMENT_REQUIRED;
            case 403 -> Response.FORBIDDEN;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            case 406 -> Response.NOT_ACCEPTABLE;
            case 407 -> Response.PROXY_AUTHENTICATION_REQUIRED;
            case 408 -> Response.REQUEST_TIMEOUT;
            case 409 -> Response.CONFLICT;
            case 410 -> Response.GONE;
            case 411 -> Response.LENGTH_REQUIRED;
            case 412 -> Response.PRECONDITION_FAILED;
            case 413 -> Response.REQUEST_ENTITY_TOO_LARGE;
            case 414 -> Response.REQUEST_URI_TOO_LONG;
            case 415 -> Response.UNSUPPORTED_MEDIA_TYPE;
            case 416 -> Response.REQUESTED_RANGE_NOT_SATISFIABLE;
            case 417 -> Response.EXPECTATION_FAILED;
            case 500 -> Response.INTERNAL_ERROR;
            case 503 -> Response.SERVICE_UNAVAILABLE;
            default -> throw new IllegalArgumentException("unknown status code: " + statusCode);
        };
    }
}
