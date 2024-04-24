package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.http.Response;
import one.nio.net.Session;
import ru.vk.itmo.test.tuzikovalexandr.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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

        String entrySize = Integer.toHexString(keyInBytes.length + valueInBytes.length + Constants.NEW_LINE.length());

        String keyInString = new String(keyInBytes, StandardCharsets.UTF_8);
        String valueInString = new String(valueInBytes, StandardCharsets.UTF_8);
        byte[] resultValue = (entrySize + Constants.CRLF +
                keyInString + Constants.NEW_LINE +
                valueInString + Constants.CRLF).getBytes(StandardCharsets.UTF_8);

        session.write(resultValue, 0, resultValue.length);
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
