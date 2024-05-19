package ru.vk.itmo.test.georgiidalbeev;

import one.nio.net.Session;
import one.nio.net.Socket;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceBaseEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class MyIterableQueueItem extends Session.QueueItem {
    private static final byte[] DELIMITER = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINAL_BYTES = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private final Iterator<ReferenceBaseEntry<MemorySegment>> entries;
    private byte[] headers;
    private byte[] currentChunk;
    private int offset;

    public MyIterableQueueItem(MyChunkedResponse myChunkedResponse) {
        this.headers = myChunkedResponse.toBytes(false);
        this.entries = myChunkedResponse.getIterator();
        this.offset = 0;
    }

    @Override
    public int write(Socket socket) throws IOException {
        int totalWritten = 0;

        if (headers != null) {
            int length = Math.min(headers.length - offset, headers.length);
            int written = socket.write(headers, offset, length);
            totalWritten += written;
            offset += written;
            if (offset < headers.length) {
                return totalWritten;
            }
            headers = null;
            offset = 0;
        }

        while (entries.hasNext() || currentChunk != null) {
            if (currentChunk == null) {
                Entry<MemorySegment> entry = entries.next();
                currentChunk = createChunk(entry);
                offset = 0;
            }
            int length = Math.min(currentChunk.length - offset, currentChunk.length);
            int written = socket.write(currentChunk, offset, length);
            totalWritten += written;
            offset += written;
            if (offset < currentChunk.length) {
                return totalWritten;
            }
            currentChunk = null;
            offset = 0;
        }

        int length = Math.min(FINAL_BYTES.length - offset, FINAL_BYTES.length);
        int written = socket.write(FINAL_BYTES, offset, length);
        totalWritten += written;
        offset += written;
        if (offset < FINAL_BYTES.length) {
            return totalWritten;
        }

        return totalWritten;
    }

    private byte[] createChunk(Entry<MemorySegment> entry) {
        byte[] size = Long.toHexString(getEntrySize(entry)).getBytes(StandardCharsets.UTF_8);
        byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
        byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);
        byte[] chunk = new byte[size.length + CRLF.length + key.length + DELIMITER.length + value.length + CRLF.length];

        int pos = 0;
        System.arraycopy(size, 0, chunk, pos, size.length);
        pos += size.length;
        System.arraycopy(CRLF, 0, chunk, pos, CRLF.length);
        pos += CRLF.length;
        System.arraycopy(key, 0, chunk, pos, key.length);
        pos += key.length;
        System.arraycopy(DELIMITER, 0, chunk, pos, DELIMITER.length);
        pos += DELIMITER.length;
        System.arraycopy(value, 0, chunk, pos, value.length);
        pos += value.length;
        System.arraycopy(CRLF, 0, chunk, pos, CRLF.length);

        return chunk;
    }

    private long getEntrySize(Entry<MemorySegment> entry) {
        return 1 + entry.key().byteSize() + entry.value().byteSize();
    }

    @Override
    public int remaining() {
        return entries.hasNext() ? 1 : 0;
    }
}
