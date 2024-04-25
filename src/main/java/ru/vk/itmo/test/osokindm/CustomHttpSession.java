package ru.vk.itmo.test.osokindm;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;
import ru.vk.itmo.test.osokindm.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class CustomHttpSession extends HttpSession {

    private static final byte[] CHUNK_SEPARATOR = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELIMITER = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZERO = "0".getBytes(StandardCharsets.UTF_8);

    public CustomHttpSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    @Override
    protected void writeResponse(Response response, boolean includeBody) throws IOException {
        if (response instanceof ChunkedResponse) {
            super.writeResponse(response, false);
            Iterator<Entry<MemorySegment>> iterator = ((ChunkedResponse) response).getResultIterator();
            while (iterator.hasNext()) {
                writeChunk(iterator.next());
            }

            super.write(ZERO, 0, ZERO.length);
            super.write(CHUNK_SEPARATOR, 0, CHUNK_SEPARATOR.length);
            super.write(CHUNK_SEPARATOR, 0, CHUNK_SEPARATOR.length);
            super.close();
        } else {
            super.writeResponse(response, includeBody);
        }
    }

    private void writeChunk(Entry<MemorySegment> entry) throws IOException {
        byte[] keyBytes = entry.key().toArray(ValueLayout.JAVA_BYTE);
        byte[] valueBytes = entry.value().toArray(ValueLayout.JAVA_BYTE);
        int entryLength = keyBytes.length + valueBytes.length + DELIMITER.length;
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + entryLength + CHUNK_SEPARATOR.length * 2);
        buffer.put(Integer.toHexString(entryLength).getBytes(StandardCharsets.UTF_8));
        buffer.put(CHUNK_SEPARATOR);
        buffer.put(entry.key().toArray(ValueLayout.JAVA_BYTE));
        buffer.put(DELIMITER);
        buffer.put(entry.value().toArray(ValueLayout.JAVA_BYTE));
        buffer.put(CHUNK_SEPARATOR);

        super.write(buffer.array(), 0, buffer.position());
    }

}
