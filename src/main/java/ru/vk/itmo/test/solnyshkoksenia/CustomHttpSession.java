package ru.vk.itmo.test.solnyshkoksenia;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.net.Socket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class CustomHttpSession extends HttpSession {
    private final HttpSession session;
    public CustomHttpSession(HttpSession session, Socket socket, HttpServer server) {
        super(socket, server);
        this.session = session;
    }

    public void chunkedWrite(String input) {
        chunkedWrite(input.getBytes(StandardCharsets.UTF_8));
    }

    public void chunkedWrite(byte[] bytes) {
        try {
            session.write(bytes, 0 , bytes.length);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
