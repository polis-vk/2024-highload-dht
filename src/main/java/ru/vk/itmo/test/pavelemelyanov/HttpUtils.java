package ru.vk.itmo.test.pavelemelyanov;

import one.nio.http.Request;
import one.nio.http.Response;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.Set;

public final class HttpUtils {
    public static final Set<Integer> METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );
    public static final int REQUEST_TIMEOUT = 3000;
    public static final Map<Integer, String> HTTP_CODE = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR
    );
    public static final int NUMBER_OF_VIRTUAL_NODES = 50;

    private HttpUtils() {

    }
}
