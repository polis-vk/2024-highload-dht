package ru.vk.itmo.test.proninvalentin;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;
import ru.vk.itmo.test.proninvalentin.streaming.StreamingResponse;

import java.io.IOException;

public class CustomHttpSession extends HttpSession {
    public CustomHttpSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    public void safetySendResponse(Response response) {
        try {
            if (response instanceof StreamingResponse streamingResponse) {
                sendStreamResponse(streamingResponse);
            } else {
                super.sendResponse(response);
            }
        } catch (IOException e) {
            log.error("Error while sending response", e);
            scheduleClose();
        }
    }

    private void sendStreamResponse(StreamingResponse response) {
        try {
            if (response.remaining() < 1) {
                safetySendResponse(new Response(Response.OK, Response.EMPTY));
                return;
            }

            response.start(socket);
            while (response.remaining() > 0) {
                response.writePart(socket);
            }
            response.finish(socket);
            close();
        } catch (IOException e) {
            log.error("Error while sending response", e);
            scheduleClose();
        }
    }
}
