package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse;

import java.io.IOException;

public class LSMCustomSession extends HttpSession {
    public LSMCustomSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    @Override
    public synchronized void sendResponse(Response response) throws IOException {
        Request handling = this.handling;
        if (handling == null) {
            throw new IOException("Out of order response");
        }

        server.incRequestsProcessed();

        boolean keepAlive = LSMConstantResponse.keepAlive(handling);

        writeResponse(response, handling.getMethod() != Request.METHOD_HEAD);
        if (!keepAlive) scheduleClose();

        this.handling = pipeline.pollFirst();
        handling = this.handling;

        if (handling != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }

    public synchronized void sendRangeResponse(LSMRangeQueueItem item) throws IOException {
        Request handling = this.handling;
        if (handling == null) {
            throw new IOException("Out of order response");
        }

        server.incRequestsProcessed();

        boolean keepAlive = LSMConstantResponse.keepAlive(handling);

        write(item);
        if (!keepAlive) scheduleClose();

        this.handling = pipeline.pollFirst();
        handling = this.handling;

        if (handling != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }
}
