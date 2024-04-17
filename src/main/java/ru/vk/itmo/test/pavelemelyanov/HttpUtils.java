package ru.vk.itmo.test.pavelemelyanov;

import one.nio.http.Request;
import one.nio.http.Response;

import java.net.HttpURLConnection;
import java.util.Map;
import java.util.Set;

public final class HttpUtils {
    public static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    public static final Set<Integer> SUPPORTED_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );
    public static final int REQUEST_TIMEOUT_IN_MILLIS = 3000;
    public static final Map<Integer, String> HTTP_CODE = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR
    );
    public static final int NUMBER_OF_VIRTUAL_NODES = 100;
    public static final String HTTP_TIMESTAMP_HEADER = "X-Timestamp";
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp:";
    public static final String HTTP_TERMINATION_HEADER = "X-Termination";

    private HttpUtils() {

    }
}
