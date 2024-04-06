package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

public class HttpUtils {
    private static final Map<Integer, String> AVAILABLE_RESPONSES;

    static {
        AVAILABLE_RESPONSES =Map.of(
                200, Response.OK,
                404, Response.NOT_FOUND,
                410, Response.GONE,
                201, Response.CREATED,
                202, Response.ACCEPTED
        ); // Immutable map.
    }

    private HttpUtils() {

    }

    public static void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
//            LOGGER.error("Error when sending a response to the client", e);
            throw new UncheckedIOException(e);
        }
    }

    public static Response getOneNioResponse(int method, ResponseElements elements) {
        switch (method) {
            case Request.METHOD_GET -> {
                String status = AVAILABLE_RESPONSES.get(elements.getStatus());

                return (status.equals(Response.GONE))
                        ? new Response(Response.NOT_FOUND, Response.EMPTY)
                        : new Response(status, elements.getBody());
            }
            case Request.METHOD_PUT -> {
                return new Response(Response.CREATED, Response.EMPTY);
            }
            default -> { // For delete method.
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
        }
    }
}
