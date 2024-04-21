package ru.vk.itmo.test.nikitaprokopev;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;

import java.io.IOException;

public class MySession extends HttpSession {
    public MySession(Socket socket, HttpServer server) {
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

            this.handling = pipeline.pollFirst();
            if ((this.handling = handling = (Request) this.pipeline.pollFirst()) != null) {
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
