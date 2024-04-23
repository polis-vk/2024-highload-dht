package ru.vk.itmo.test.volkovnikita.util;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;

import java.io.IOException;

public class CustomSession extends HttpSession {

    public CustomSession(Socket connectionSocket, HttpServer hostServer) {
        super(connectionSocket, hostServer);
    }

    @Override
    public synchronized void sendResponse(Response response) throws IOException {
        if (response instanceof ChunkHttpResponse chunkHttpResponse) {
            server.incRequestsProcessed();
            write(new ChunkQueueItem(chunkHttpResponse));

            this.handling = nextRequestInPipeline();
            if (this.handling != null) {
                if (this.handling == FIN) {
                    initiateClose();
                } else {
                    server.handleRequest(this.handling, this);
                }
            }
        } else {
            super.sendResponse(response);
        }
    }

    private Request nextRequestInPipeline() {
        return this.pipeline.pollFirst();
    }

    private void initiateClose() {
        scheduleClose();
    }
}
