package ru.vk.itmo.test.reference;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;
import ru.vk.itmo.test.reference.dao2.ReferenceBaseEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class ReferenceHttpSession extends HttpSession {
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LF = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    Iterator<ReferenceBaseEntry<MemorySegment>> iterator;

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

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();

        nextChunk();
    }

    public void stream(Iterator<ReferenceBaseEntry<MemorySegment>> iterator) throws IOException {
        this.iterator = iterator;
        Response response = new Response(Response.OK);
        response.addHeader("Transfer-Encoding: chunked");
        writeResponse(response, false);

        nextChunk();
    }

    private void nextChunk() throws IOException {
        while (iterator.hasNext() && queueHead == null) {
            ReferenceBaseEntry<MemorySegment> next = iterator.next();
            ByteBuffer key = next.key().asByteBuffer();
            ByteBuffer value = next.value().asByteBuffer();
            int payloadSize = key.remaining() + value.remaining() + LF.length;
            String payloadSizeStr = Integer.toHexString(payloadSize);
            byte[] payloadSizeStrBytes = payloadSizeStr.getBytes(StandardCharsets.UTF_8);
            write(payloadSizeStrBytes, 0, payloadSizeStrBytes.length);
            write(CRLF, 0, CRLF.length);
            write(new ReferenceQueueItem(key));
            write(LF, 0, LF.length);
            write(new ReferenceQueueItem(value));
            write(CRLF, 0, CRLF.length);
        }

        if (!iterator.hasNext()) {
            write(EMPTY_CHUNK, 0, EMPTY_CHUNK.length);

            if ((this.handling = pipeline.pollFirst()) != null) {
                if (handling == FIN) {
                    scheduleClose();
                } else {
                    server.handleRequest(handling, this);
                }
            }
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

    static class ReferenceQueueItem extends QueueItem {
        private final ByteBuffer buffer;

        ReferenceQueueItem(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public int remaining() {
            return buffer.remaining();
        }

        @Override
        public int write(Socket socket) throws IOException {
            return socket.write(buffer);
        }
    }
}
