package ru.vk.itmo.test.georgiidalbeev;

import one.nio.net.Session;
import one.nio.net.Socket;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceBaseEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class MyIterableQueueItem extends Session.QueueItem {
    private static final String DELIMITER = "\n";
    private static final String CRLF = "\r\n";
    private static final String FINAL_BYTES = "0\r\n\r\n";
    private final Iterator<ReferenceBaseEntry<MemorySegment>> entries;
    private byte[] headers;

    public MyIterableQueueItem(MyChunkedResponse myChunkedResponse) {
        this.headers = myChunkedResponse.toBytes(false);
        this.entries = myChunkedResponse.getIterator();
    }

    @Override
    public int write(Socket socket) throws IOException {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        if (headers != null) {
            builder.append(headers);
            headers = null;
        }
        if (entries.hasNext()) {
            Entry<MemorySegment> entry = entries.next();
            createChunk(builder, entry);
        }
        if (!entries.hasNext()) {
            builder.append(FINAL_BYTES.getBytes(StandardCharsets.UTF_8));
        }
        return socket.write(builder.toBytes(), 0, builder.length());
    }

    private void createChunk(ByteArrayBuilder builder, Entry<MemorySegment> entry) {
        builder.append(Long.toHexString(getEntrySize(entry)).getBytes(StandardCharsets.UTF_8));
        builder.append(CRLF);
        builder.append(entry.key().toArray(ValueLayout.JAVA_BYTE));
        builder.append(DELIMITER);
        builder.append(entry.value().toArray(ValueLayout.JAVA_BYTE));
        builder.append(CRLF);
    }

    private long getEntrySize(Entry<MemorySegment> entry) {
        return 1 + entry.key().byteSize() + entry.value().byteSize();
    }

    @Override
    public int remaining() {
        return entries.hasNext() ? 1 : 0;
    }
}
