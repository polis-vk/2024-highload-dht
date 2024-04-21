package ru.vk.itmo.test.nikitaprokopev;

import one.nio.net.Session;
import one.nio.net.Socket;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.nikitaprokopev.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class MyIterableQueueItem extends Session.QueueItem {
    private static final String DELIMITER = "\n";
    private static final String CRLF = "\r\n";
    private static final String FINAL_BYTES = "0\r\n\r\n";
    private final Iterator<Entry<MemorySegment>> entries;
    private byte[] headers;

    public MyIterableQueueItem(MyChunkedResponse myChunkedResponse) {
        this.entries = myChunkedResponse.getIterator();
        this.headers = myChunkedResponse.toBytes(false);
    }

    @Override
    public int remaining() {
        return entries.hasNext() ? 1 : 0;
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
        builder.append(toByteArray(entry.key()));
        builder.append(DELIMITER);
        builder.append(toByteArray(entry.value()));
        builder.append(CRLF);
    }

    private long getEntrySize(Entry<MemorySegment> entry) {
        long size = entry.key().byteSize();
        size += 1; // delimiter
        size += entry.value().byteSize();
        return size;
    }

    private byte[] toByteArray(MemorySegment data) {
        return data.toArray(ValueLayout.JAVA_BYTE);
    }
}
