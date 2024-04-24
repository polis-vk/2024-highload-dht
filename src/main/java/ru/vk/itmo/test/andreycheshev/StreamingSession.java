package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.andreycheshev.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamingSession extends HttpSession {
    private static final Logger LOGGER = LoggerFactory.getLogger(StreamingSession.class);
    private static final byte[] CRLF = "\\r\\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LF = "\\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] END_PART = "0\\r\\n\\r\\n".getBytes(StandardCharsets.UTF_8);

    private final Semaphore semaphore = new Semaphore(0);

    private final AtomicBoolean wasFirstWrite = new AtomicBoolean(false);

    private static final int BUFF_SIZE = 1024 * 1024; // 1 mb.
    private final StreamingBuffer buffer = new StreamingBuffer(BUFF_SIZE, this);

    public StreamingSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    @SuppressWarnings("DefaultCharset")
    public synchronized void stream(Iterator<Entry<MemorySegment>> streamIterator) throws IOException, InterruptedException {
        Request handling = this.handling;
        if (handling == null) {
            throw new IOException("Out of order response");
        }

        Response response = new Response(Response.OK);

        String connection = handling.getHeader("Connection:");
        boolean keepAlive = handling.isHttp11()
                ? !"close".equalsIgnoreCase(connection)
                : "Keep-Alive".equalsIgnoreCase(connection);
        response.addHeader(keepAlive ? "Connection: Keep-Alive" : "Connection: close");
        response.addHeader("Transfer-Encoding: chunked");

        byte[] responseBytes = response.toBytes(false);
        buffer.copyIn(responseBytes);
        buffer.copyIn(CRLF);

        while (streamIterator.hasNext()) {
            Entry<MemorySegment> entry = streamIterator.next();

            byte[] keyBytes = entry.key().toArray(ValueLayout.JAVA_BYTE);
            byte[] valueBytes = entry.value().toArray(ValueLayout.JAVA_BYTE);
            int entrySize = keyBytes.length + LF.length + valueBytes.length;

            byte[] lengthBytes = String.valueOf(entrySize).getBytes();
            int lengthSize = lengthBytes.length;

            checkForFlush(buffer.size() + lengthSize + entrySize + 2 * CRLF.length);

            appendEntryToBuffer(lengthBytes, keyBytes, valueBytes);
        }

        checkForFlush(buffer.size() + END_PART.length);
        buffer.copyIn(END_PART);
        flush();

        wasFirstWrite.set(false);

        if ((this.handling = pipeline.pollFirst()) != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                server.handleRequest(handling, this);
            }
        }
    }

    private void appendEntryToBuffer(byte[] length, byte[] key, byte[] value) {
        buffer.copyIn(length);
        buffer.copyIn(CRLF);
        buffer.copyIn(key);
        buffer.copyIn(LF);
        buffer.copyIn(value);
        buffer.copyIn(CRLF);
    }

    private void checkForFlush(int size) throws InterruptedException, IOException {
        if (size > BUFF_SIZE) {
            flush();
        }
    }

    private void flush() throws IOException, InterruptedException {
        if (!wasFirstWrite.getAndSet(true)) {
            buffer.write();
        } else {
            buffer.setReadyToWrite();
            semaphore.acquire();
        }
        buffer.reset();
    }

    private void streamingWrite() throws IOException {
        LOGGER.info("streamingWrite");
        buffer.writeIfReady();
        semaphore.release();
    }

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();
        streamingWrite();
    }
}
