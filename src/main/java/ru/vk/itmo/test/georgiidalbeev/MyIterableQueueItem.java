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

    public MyIterableQueueItem(MyChunkedResponse myChunkedResponse) {
        this.headers = myChunkedResponse.toBytes(false);
        this.entries = myChunkedResponse.getIterator();
    }

    @Override
    public int write(Socket socket) throws IOException {
        int written = 0;
        if (headers != null) {
            written += socket.write(headers, 0, headers.length);
            headers = null;
        }
        if (entries.hasNext()) {
            Entry<MemorySegment> entry = entries.next();
            written += writeToSocket(socket, entry);
        }
        if (!entries.hasNext()) {
            written += socket.write(FINAL_BYTES, 0, FINAL_BYTES.length);
        }
        return written;
    }

    private int writeToSocket(Socket socket, Entry<MemorySegment> entry) throws IOException {
        int written = 0;
        byte[] size = Long.toHexString(getEntrySize(entry)).getBytes(StandardCharsets.UTF_8);
        written += socket.write(size, 0, size.length);
        written += socket.write(CRLF, 0, CRLF.length);
        byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
        written += socket.write(key, 0, key.length);
        written += socket.write(DELIMITER, 0, DELIMITER.length);
        byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);
        written += socket.write(value, 0, value.length);
        written += socket.write(CRLF, 0, CRLF.length);
        return written;
    }

    private long getEntrySize(Entry<MemorySegment> entry) {
        return 1 + entry.key().byteSize() + entry.value().byteSize();
    }

    @Override
    public int remaining() {
        return entries.hasNext() ? 1 : 0;
    }
}
