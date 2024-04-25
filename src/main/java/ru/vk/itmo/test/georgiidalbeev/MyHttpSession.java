package ru.vk.itmo.test.georgiidalbeev;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;

import java.io.IOException;

public class MyHttpSession extends HttpSession {
    public MyHttpSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    @Override
    public synchronized void sendResponse(Response response) throws IOException {
        if (response instanceof MyChunkedResponse myChunkedResponse) {
            Request handling = this.handling;
            if (handling == null) {
                throw new IOException("Out of order response");
            }
            server.incRequestsProcessed();
            write(new MyIterableQueueItem(myChunkedResponse));
            this.handling = handling = this.pipeline.pollFirst();
            if (handling != null) {
                if (handling == FIN) {
                    scheduleClose();
                } else {
                    server.handleRequest(handling, this);
                }
            }
        } else {
            super.sendResponse(response);
        }
    }
}
