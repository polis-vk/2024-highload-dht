package ru.vk.itmo.test.reference;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;

import java.io.IOException;

public class ReferenceHttpSession extends HttpSession {
    public ReferenceHttpSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    public void sendResponseOrClose(Response response) {
        try {
            sendResponse(response);
        } catch (IOException e) {
            log.error("Exception while sending close connection", e);
            scheduleClose();
        }
    }

    public void sendError(Throwable e) {
        log.error("Exception during handleRequest", e);
        try {
            sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            log.error("Exception while sending close connection", e);
            scheduleClose();
        }
    }
}
