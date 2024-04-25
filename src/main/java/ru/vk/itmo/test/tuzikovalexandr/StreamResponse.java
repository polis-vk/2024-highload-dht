package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.http.Response;
import one.nio.net.Session;
import ru.vk.itmo.test.tuzikovalexandr.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class StreamResponse extends Response {
    private final Iterator<EntryWithTimestamp<MemorySegment>> entries;

    public StreamResponse(String resultCode, Iterator<EntryWithTimestamp<MemorySegment>> entries) {
        super(resultCode);
        this.entries = entries;
    }

    public void stream(Session session) throws IOException {
        session.write(Constants.HEADER, 0, Constants.HEADER.length);

        processWrite(session);
        session.scheduleClose();
    }

    private void next(Session session, MemorySegment key, MemorySegment value) throws IOException {
        byte[] keyInBytes = toByteArray(key);
        byte[] valueInBytes = toByteArray(value);

        byte[] entrySize = Integer.toHexString(keyInBytes.length + valueInBytes.length + Constants.NEW_LINE.length)
                .getBytes(StandardCharsets.UTF_8);

        byte[] resultValue = new byte[keyInBytes.length + valueInBytes.length + Constants.NEW_LINE.length
                + Constants.CRLF.length * 2 + entrySize.length];

        ByteBuffer target = ByteBuffer.wrap(resultValue);
        target.put(entrySize);
        target.put(Constants.CRLF);
        target.put(keyInBytes);
        target.put(Constants.NEW_LINE);
        target.put(valueInBytes);
        target.put(Constants.CRLF);

        session.write(target.array(), 0, target.array().length);
    }

    private void processWrite(Session session) throws IOException {
        while (entries.hasNext()) {
            EntryWithTimestamp<MemorySegment> entry = entries.next();

            next(session, entry.key(), entry.value());
        }
        session.write(Constants.LAST_STRING, 0, Constants.LAST_STRING.length);
    }

    private <T> byte[] toByteArray(T segment) {
        if (segment == null) {
            throw new IllegalArgumentException();
        }

        if (segment instanceof MemorySegment) {
            return ((MemorySegment) segment).toArray(ValueLayout.JAVA_BYTE);
        }

        if (segment instanceof String) {
            return ((String) segment).getBytes(StandardCharsets.UTF_8);
        }

        return new byte[0];
    }
}
