package ru.vk.itmo.test.pavelemelyanov;

import one.nio.http.Response;
import one.nio.net.Session;
import ru.vk.itmo.test.pavelemelyanov.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class StreamResponse extends Response {
    public static final String CRLF = "\r\n";
    public static final byte[] LAST_STRING = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    public static final String NEW_LINE = "\n";
    public static final byte[] HEADER = getHeaderBytes();

    private final Iterator<EntryWithTimestamp<MemorySegment>> entries;

    public StreamResponse(String resultCode, Iterator<EntryWithTimestamp<MemorySegment>> entries) {
        super(resultCode);
        this.entries = entries;
    }

    public void stream(Session session) throws IOException {
        session.write(HEADER, 0, HEADER.length);

        processWrite(session);
        session.scheduleClose();
    }

    private void next(Session session, MemorySegment key, MemorySegment value) throws IOException {
        if (value == null || key == null) {
            throw new IllegalArgumentException();
        }

        byte[] keyInBytes = key.toArray(ValueLayout.JAVA_BYTE);
        byte[] valueInBytes = value.toArray(ValueLayout.JAVA_BYTE);

        String entrySize = Integer.toHexString(keyInBytes.length + valueInBytes.length + NEW_LINE.length());

        var keyInString = new String(keyInBytes, StandardCharsets.UTF_8);
        var valueInString = new String(valueInBytes, StandardCharsets.UTF_8);
        byte[] resultValue = (entrySize + CRLF
                + keyInString + NEW_LINE
                + valueInString + CRLF).getBytes(StandardCharsets.UTF_8);

        session.write(resultValue, 0, resultValue.length);
    }

    private void processWrite(Session session) throws IOException {
        while (entries.hasNext()) {
            EntryWithTimestamp<MemorySegment> entry = entries.next();

            next(session, entry.key(), entry.value());
        }
        session.write(LAST_STRING, 0, LAST_STRING.length);
    }

    private static byte[] getHeaderBytes() {
        return """
                    HTTP/1.1 200 OK\r
                    Content-Type: text/plain\r
                    Transfer-Encoding: chunked\r
                    \r
                    """.getBytes(StandardCharsets.UTF_8);
    }
}
