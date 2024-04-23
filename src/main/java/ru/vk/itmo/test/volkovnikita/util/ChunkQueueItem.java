package ru.vk.itmo.test.volkovnikita.util;

import one.nio.net.Session;
import one.nio.net.Socket;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.volkovnikita.dao.TimestampEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class ChunkQueueItem extends Session.QueueItem {

    private final Iterator<TimestampEntry<MemorySegment>> chunkIterator;
    private byte[] headers;

    public ChunkQueueItem(ChunkHttpResponse response) {
        this.chunkIterator = response.getIterator();
        this.headers = response.toBytes(false);
    }

    @Override
    public int remaining() {
        return chunkIterator.hasNext() ? 1 : 0;
    }

    @Override
    public int write(Socket connection) throws IOException {
        ByteArrayBuilder output = new ByteArrayBuilder();

        if (headers != null) {
            output.append(headers);
            headers = null;
        }
        while (chunkIterator.hasNext()) {
            appendChunk(output, chunkIterator.next());
        }
        output.append("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        return connection.write(output.toBytes(), 0, output.length());
    }

    private void appendChunk(ByteArrayBuilder output, Entry<MemorySegment> chunk) {
        output.append(Long.toHexString(toEntrySize(chunk)).getBytes(StandardCharsets.UTF_8));
        output.append("\r\n".getBytes(StandardCharsets.UTF_8));
        output.append(convertToByteArray(chunk.key()));
        output.append("\n".getBytes(StandardCharsets.UTF_8));
        output.append(convertToByteArray(chunk.value()));
        output.append("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private long toEntrySize(Entry<MemorySegment> entry) {
        return entry.key().byteSize() + 1 + entry.value().byteSize();
    }

    private byte[] convertToByteArray(MemorySegment data) {
        return data.toArray(ValueLayout.JAVA_BYTE);
    }
}
