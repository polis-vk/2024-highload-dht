package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import one.nio.net.Socket;
import ru.vk.itmo.test.andreycheshev.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class StreamingSession extends HttpSession {
    private static final int BUFF_SIZE = (2 << 10) * (2 << 10);

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LF = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] END_PART = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    public StreamingSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    public synchronized void stream(
            Iterator<Entry<MemorySegment>> streamIterator) throws IOException, InterruptedException {

        Request handling = this.handling;
        if (handling == null) {
            throw new IOException("Out of order response");
        }

        write(new RangeItem(streamIterator, handling));

        if ((this.handling = pipeline.pollFirst()) != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }

    public static class RangeItem extends Session.QueueItem {
        private final Iterator<Entry<MemorySegment>> rangeIterator;
        private final StreamingBuffer buffer = new StreamingBuffer(BUFF_SIZE);
        private Entry<MemorySegment> entry;

        public RangeItem(Iterator<Entry<MemorySegment>> iterator, Request request) {
            this.rangeIterator = iterator;
            initResponse(request);
        }

        private void initResponse(Request request) {
            Response response = new Response(Response.OK);
            String connection = request.getHeader("Connection:");
            boolean keepAlive = request.isHttp11()
                    ? !"close".equalsIgnoreCase(connection)
                    : "Keep-Alive".equalsIgnoreCase(connection);
            response.addHeader(keepAlive ? "Connection: Keep-Alive" : "Connection: close");
            response.addHeader("Transfer-Encoding: chunked");

            buffer.append(response.toBytes(false));
        }

        @Override
        public int remaining() {
            return entry != null || rangeIterator.hasNext() || !buffer.isEmpty() ? 1 : 0;
        }

        @Override
        public int write(Socket socket) throws IOException {

            buffer.tryWrite(socket);

            while (entry != null || rangeIterator.hasNext()) {
                if (entry == null) {
                    entry = rangeIterator.next();
                }

                byte[] keyBytes = entry.key().toArray(ValueLayout.JAVA_BYTE);
                byte[] valueBytes = entry.value().toArray(ValueLayout.JAVA_BYTE);
                int entrySize = keyBytes.length + LF.length + valueBytes.length;
                byte[] lengthBytes = Integer.toHexString(entrySize).getBytes(StandardCharsets.UTF_8);

                if (!buffer.isFits(lengthBytes.length + entrySize + 2 * CRLF.length + END_PART.length)) {
                    break;
                }

                writeEntry(lengthBytes, keyBytes, valueBytes);
                entry = null;
            }

            if (entry == null && !rangeIterator.hasNext()) {
                buffer.append(END_PART);
            }

            return buffer.tryWrite(socket);
        }

        private void writeEntry(byte[] length, byte[] key, byte[] value) {
            buffer.append(length);
            buffer.append(CRLF);
            buffer.append(key);
            buffer.append(LF);
            buffer.append(value);
            buffer.append(CRLF);
        }
    }
}
