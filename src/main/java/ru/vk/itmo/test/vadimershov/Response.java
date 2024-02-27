package ru.vk.itmo.test.vadimershov;

public class Response extends one.nio.http.Response {

    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";

    public Response(String resultCode, byte[] body) {
        super(resultCode, body);
    }

    public static Response ok(byte[] body) {
        return new Response(OK, body);
    }

    public static Response empty(String code) {
        return new Response(code, Response.EMPTY);
    }

}
