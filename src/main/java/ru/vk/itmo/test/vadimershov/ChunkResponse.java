package ru.vk.itmo.test.vadimershov;

import one.nio.net.Session;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static ru.vk.itmo.test.vadimershov.utils.MSUtil.toByteArray;

public class ChunkResponse {
    private static final byte[] HEADER = """
            HTTP/1.1 200 OK\r
            Transfer-Encoding: chunked\r
            Connection: close\r
            \r
            """.getBytes(StandardCharsets.UTF_8);

    private static final byte[] END = """
            0\r
            \r
            """.getBytes(StandardCharsets.UTF_8);

    private final Session session;

    public ChunkResponse(Session session) throws IOException {
        this.session = session;
        this.session.write(HEADER, 0, HEADER.length);
    }

    public void send(Entry<MemorySegment> entry) throws IOException {
        this.send(entry, '\n');
    }

    public void send(Entry<MemorySegment> entry, char separator) throws IOException {
        int chunkSize = (int) (1 + entry.key().byteSize() + entry.value().byteSize());

        ByteArrayBuilder builder = new ByteArrayBuilder(chunkSize);
        // add key
        append(builder,toByteArray(entry.key()));

        // add separate
        append(builder, separator);

        // add value
        if (entry.value().byteSize() > 0) {
            append(builder, toByteArray(entry.value()));
        }

        session.write(builder.toBytes(), 0, builder.toBytes().length);
    }

    public void end() throws IOException {
        session.write(END, 0, END.length);
    }

    private void append(ByteArrayBuilder builder, char data) {
        builder.append(1);
        builder.append('\r').append('\n');
        builder.append(data);
        builder.append('\r').append('\n');
    }

    private void append(ByteArrayBuilder builder, byte[] data) {
        builder.append(data.length);
        builder.append('\r').append('\n');
        builder.append(data);
        builder.append('\r').append('\n');
    }

}
