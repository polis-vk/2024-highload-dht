package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

public class HttpUtils {
    public static final int OK_CODE = 200;
    public static final int CREATED_CODE = 201;
    public static final int ACCEPT_CODE = 202;
    public static final int NOT_FOUND_CODE = 404;
    public static final int GONE_CODE = 410;

    public static final Map<Integer, String> AVAILABLE_RESPONSES = Map.of(
            OK_CODE, Response.OK,
            CREATED_CODE, Response.CREATED,
            ACCEPT_CODE, Response.ACCEPTED,
            NOT_FOUND_CODE, Response.NOT_FOUND,
            GONE_CODE, Response.GONE
    );

    public static final String TIMESTAMP_JAVA_NET_HEADER = "X-Timestamp";
    public static final String TIMESTAMP_ONE_NIO_HEADER = TIMESTAMP_JAVA_NET_HEADER + ": ";

    private static final String NOT_ENOUGH_REPLICAS_STATUS = "504 Not Enough Replicas";
    private static final String TOO_MANY_REQUESTS_STATUS = "429 Too many requests";

    public static final long EMPTY_TIMESTAMP = -1;

    private HttpUtils() {

    }

    public static Response getBadRequest() {
        return new Response(Response.BAD_REQUEST, Response.EMPTY);
    }

    public static Response getMethodNotAllowed() {
        return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
    }

    public static Response getInternalError() {
        return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
    }

    public static Response getNotEnoughReplicas() {
        return new Response(NOT_ENOUGH_REPLICAS_STATUS, Response.EMPTY);
    }

    public static Response getTooManyRequests() {
        return new Response(TOO_MANY_REQUESTS_STATUS, Response.EMPTY);
    }

    public static void sendResponse(Response response, HttpSession session) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            throw new SendingResponseException(e);
        }
    }

    public static ResponseElements getElementsFromJavaNetResponse(HttpResponse<byte[]> response) {
        Optional<String> optTimestamp = response.headers().firstValue(TIMESTAMP_JAVA_NET_HEADER);

        long responseTimestamp = optTimestamp.map(Long::parseLong).orElse(EMPTY_TIMESTAMP);

        return new ResponseElements(
                response.statusCode(),
                response.body(),
                responseTimestamp
        );
    }

    public static Response getOneNioResponse(int method, ResponseElements elements) {
        switch (method) {
            case Request.METHOD_GET -> {
                int status = elements.getStatus();

                Response response = status == GONE_CODE
                        ? new Response(Response.NOT_FOUND, Response.EMPTY)
                        : new Response(AVAILABLE_RESPONSES.get(status), elements.getBody());

                response.addHeader(TIMESTAMP_ONE_NIO_HEADER + elements.getTimestamp());

                return response;
            }
            case Request.METHOD_PUT -> {
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> throw new IllegalArgumentException("Unsupported request method");
        }
    }
}
