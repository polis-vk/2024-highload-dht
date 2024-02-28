package ru.vk.itmo.test.ryabovvadim.utils;

import one.nio.http.Response;

public final class HttpServerConstants {

    public static final Response NOT_FOUND = new Response(Response.NOT_FOUND, Response.EMPTY);
    public static final Response BAD_REQUEST = new Response(Response.BAD_REQUEST, Response.EMPTY);
    public static final Response ACCEPTED = new Response(Response.ACCEPTED, Response.EMPTY);
    public static final Response CREATED = new Response(Response.CREATED, Response.EMPTY);

    private HttpServerConstants() {
    }
}
