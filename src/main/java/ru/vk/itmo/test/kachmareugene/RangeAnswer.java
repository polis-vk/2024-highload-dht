package ru.vk.itmo.test.kachmareugene;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.kachmareugene.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class RangeAnswer {
    public static final String RANGE_REQUEST_PATH = "/v0/entities";
    public static final String CHUNK_TRANSFER_HEADERS =
            new StringBuilder()
                    .append("HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: text/plain\r\n")
                    .append("Transfer-Encoding: chunked\r\n")
                    .append("Connection: keep-alive\r\n")
                    .append("\r\n").toString();

    public static final String SEP = "\r\n";

    public void handleRange(Request request,
                            HttpSession session,
                            Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> daoImpl) throws IOException {

        String start = request.getParameter("start=");
        if (start == null || start.isEmpty() || start.isBlank()) {
            session.sendError(Response.BAD_REQUEST, "Void start parameter.");
            return;
        }

        Iterator<EntryWithTimestamp<MemorySegment>> iter =
                daoImpl.get(fromString(start),
                        fromString(request.getParameter("end=")));

        if (!iter.hasNext()) {
            session.sendResponse(new Response(Response.OK));
            return;
        }

        writeDataIntoSession(session, CHUNK_TRANSFER_HEADERS);

        while (iter.hasNext()) {
            EntryWithTimestamp<MemorySegment> entry = iter.next();
            byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
            byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);

            writeDataIntoSession(session,
                    Integer.toHexString(
                    key.length
                            + "\n".length()
                            + value.length)
                            + SEP);
            writeDataIntoSession(session, key);
            writeDataIntoSession(session, "\n");
            writeDataIntoSession(session, value);
            writeDataIntoSession(session, SEP);
        }

        // correct ending
        writeDataIntoSession(session, "0" + SEP);
        writeDataIntoSession(session, SEP);
    }

    private void writeDataIntoSession(Session session, String data) throws IOException {
        byte[] d = data.getBytes(StandardCharsets.UTF_8);
        session.write(d, 0, d.length);
    }

    private void writeDataIntoSession(Session session, byte[] data) throws IOException {
        session.write(data, 0, data.length);
    }

    private MemorySegment fromString(String data) {
        if (data == null) {
            return null;
        }
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }
}
