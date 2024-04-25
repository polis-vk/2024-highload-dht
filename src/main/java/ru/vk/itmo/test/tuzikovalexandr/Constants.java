package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.http.Request;
import one.nio.http.Response;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

public final class Constants {
    public static final Set<Integer> METHODS = Set.of(
            Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE
    );
    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    public static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    public static final int SERVER_ERROR = 500;
    public static final int REQUEST_TIMEOUT = 500;
    public static final Map<Integer, String> HTTP_CODE = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR
    );
    public static final String BASE_URL = "http://localhost";
    public static final int BASE_PORT = 8080;
    public static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;
    public static final int NUMBER_OF_VIRTUAL_NODES = 100;
    public static final String HTTP_TIMESTAMP_HEADER = "X-Timestamp";
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp:";
    public static final String HTTP_TERMINATION_HEADER = "X-Termination";
    public static final String RANGE_REQUEST = "/v0/entities?start=";
    public static final String ID_REQUEST = "/v0/entity?id=";
    public static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] LAST_STRING = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] NEW_LINE = "\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] HEADER =
            """
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain\r
                    Transfer-Encoding: chunked\r
                    \r
                    """.getBytes(StandardCharsets.UTF_8);

    private Constants() {
    }
}
