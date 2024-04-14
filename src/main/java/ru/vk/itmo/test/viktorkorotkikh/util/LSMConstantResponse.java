package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.Request;
import one.nio.http.Response;

public final class LSMConstantResponse {
    private static final String CONNECTION_CLOSE_HEADER = "Connection: close";
    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    public static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    public static final Response BAD_REQUEST_CLOSE = new Response(Response.BAD_REQUEST, Response.EMPTY);
    public static final Response CREATED_CLOSE = new Response(Response.CREATED, Response.EMPTY);
    public static final Response ACCEPTED_CLOSE = new Response(Response.ACCEPTED, Response.EMPTY);
    public static final Response NOT_FOUND_CLOSE = new Response(Response.NOT_FOUND, Response.EMPTY);
    public static final Response METHOD_NOT_ALLOWED_CLOSE = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    public static final Response OK_CLOSE = new Response(Response.OK, Response.EMPTY);
    public static final Response TOO_MANY_REQUESTS_CLOSE = new Response(TOO_MANY_REQUESTS, Response.EMPTY);
    public static final Response ENTITY_TOO_LARGE_CLOSE =
            new Response(Response.REQUEST_ENTITY_TOO_LARGE, Response.EMPTY);
    public static final Response SERVICE_UNAVAILABLE_CLOSE =
            new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);

    public static final Response GATEWAY_TIMEOUT_CLOSE =
            new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    public static final Response NOT_ENOUGH_REPLICAS_CLOSE = new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);

    private static final String CONNECTION_KEEP_ALIVE_HEADER = "Connection: Keep-Alive";
    public static final Response BAD_REQUEST_KEEP_ALIVE = new Response(Response.BAD_REQUEST, Response.EMPTY);
    public static final Response CREATED_KEEP_ALIVE = new Response(Response.CREATED, Response.EMPTY);
    public static final Response ACCEPTED_KEEP_ALIVE = new Response(Response.ACCEPTED, Response.EMPTY);
    public static final Response NOT_FOUND_KEEP_ALIVE = new Response(Response.NOT_FOUND, Response.EMPTY);
    public static final Response METHOD_NOT_ALLOWED_KEEP_ALIVE
            = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    public static final Response OK_KEEP_ALIVE = new Response(Response.OK, Response.EMPTY);
    public static final Response TOO_MANY_REQUESTS_KEEP_ALIVE = new Response(TOO_MANY_REQUESTS, Response.EMPTY);
    public static final Response ENTITY_TOO_LARGE_KEEP_ALIVE =
            new Response(Response.REQUEST_ENTITY_TOO_LARGE, Response.EMPTY);
    public static final Response SERVICE_UNAVAILABLE_KEEP_ALIVE
            = new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY);
    public static final Response GATEWAY_TIMEOUT_KEEP_ALIVE =
            new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    public static final Response NOT_ENOUGH_REPLICAS_KEEP_ALIVE =
            new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);

    static {
        BAD_REQUEST_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        CREATED_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        ACCEPTED_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        NOT_FOUND_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        METHOD_NOT_ALLOWED_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        OK_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        TOO_MANY_REQUESTS_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        ENTITY_TOO_LARGE_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        SERVICE_UNAVAILABLE_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        GATEWAY_TIMEOUT_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);
        NOT_ENOUGH_REPLICAS_CLOSE.addHeader(CONNECTION_CLOSE_HEADER);

        BAD_REQUEST_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        CREATED_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        ACCEPTED_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        NOT_FOUND_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        METHOD_NOT_ALLOWED_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        OK_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        TOO_MANY_REQUESTS_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        ENTITY_TOO_LARGE_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        SERVICE_UNAVAILABLE_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        GATEWAY_TIMEOUT_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
        NOT_ENOUGH_REPLICAS_KEEP_ALIVE.addHeader(CONNECTION_KEEP_ALIVE_HEADER);
    }

    public static Response ok(final Request request) {
        return keepAlive(request) ? OK_KEEP_ALIVE : OK_CLOSE;
    }

    public static Response badRequest(final Request request) {
        return keepAlive(request) ? BAD_REQUEST_KEEP_ALIVE : BAD_REQUEST_CLOSE;
    }

    public static Response created(final Request request) {
        return keepAlive(request) ? CREATED_KEEP_ALIVE : CREATED_CLOSE;
    }

    public static Response accepted(final Request request) {
        return keepAlive(request) ? ACCEPTED_KEEP_ALIVE : ACCEPTED_CLOSE;
    }

    public static Response notFound(final Request request) {
        return keepAlive(request) ? NOT_FOUND_KEEP_ALIVE : NOT_FOUND_CLOSE;
    }

    public static Response methodNotAllowed(final Request request) {
        return keepAlive(request) ? METHOD_NOT_ALLOWED_KEEP_ALIVE : METHOD_NOT_ALLOWED_CLOSE;
    }

    public static Response tooManyRequests(Request request) {
        return keepAlive(request) ? TOO_MANY_REQUESTS_KEEP_ALIVE : TOO_MANY_REQUESTS_CLOSE;
    }

    public static Response entityTooLarge(Request request) {
        return keepAlive(request) ? ENTITY_TOO_LARGE_KEEP_ALIVE : ENTITY_TOO_LARGE_CLOSE;
    }

    public static Response serviceUnavailable(Request request) {
        return keepAlive(request) ? SERVICE_UNAVAILABLE_KEEP_ALIVE : SERVICE_UNAVAILABLE_CLOSE;
    }

    public static Response gatewayTimeout(Request request) {
        return keepAlive(request) ? GATEWAY_TIMEOUT_KEEP_ALIVE : GATEWAY_TIMEOUT_CLOSE;
    }

    public static Response notEnoughReplicas(Request request) {
        return keepAlive(request) ? NOT_ENOUGH_REPLICAS_KEEP_ALIVE : NOT_ENOUGH_REPLICAS_CLOSE;
    }

    public static boolean keepAlive(final Request request) {
        final String connection = request.getHeader("Connection:");
        return request.isHttp11()
                ? !"close".equalsIgnoreCase(connection)
                : "Keep-Alive".equalsIgnoreCase(connection);
    }

    private LSMConstantResponse() {
    }
}
