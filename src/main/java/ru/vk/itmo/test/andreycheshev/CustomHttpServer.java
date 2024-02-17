package ru.vk.itmo.test.andreycheshev;

import one.nio.http.*;

import java.io.IOException;

import static one.nio.http.Request.*;

public class CustomHttpServer extends HttpServer {
    private static final String REQUEST_PATH = "/v0/entity";

    public CustomHttpServer(HttpServerConfig config, Object... routers) throws IOException {
        super(config, routers);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String path = request.getPath();
        if (!path.equals(REQUEST_PATH)) {
            Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
            session.sendResponse(response);
            return;
        }

        int method = request.getMethod();
        if (method != METHOD_GET && method != METHOD_PUT && method != METHOD_DELETE) {
            Response response = new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            session.sendResponse(response);
            return;
        }

        super.handleRequest(request, session);
    }
}
