package ru.vk.itmo.test.kovalevigor.server.strategy;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;
import ru.vk.itmo.test.kovalevigor.dao.DaoImpl;
import ru.vk.itmo.test.kovalevigor.dao.SSTimeTableManager;
import ru.vk.itmo.test.kovalevigor.dao.entry.DaoEntry;
import ru.vk.itmo.test.kovalevigor.dao.entry.MSegmentTimeEntry;
import ru.vk.itmo.test.kovalevigor.dao.entry.TimeEntry;
import ru.vk.itmo.test.kovalevigor.dao.iterators.ApplyIterator;
import ru.vk.itmo.test.kovalevigor.dao.iterators.ShiftedIterator;
import ru.vk.itmo.test.kovalevigor.server.util.CustomQueueItem;
import ru.vk.itmo.test.kovalevigor.server.util.Headers;
import ru.vk.itmo.test.kovalevigor.server.util.Parameters;
import ru.vk.itmo.test.kovalevigor.server.util.Paths;
import ru.vk.itmo.test.kovalevigor.server.util.Responses;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.logging.Logger;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.createIllegalState;

public class ServerDaoStrategy extends ServerRejectStrategy {
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private final Dao<MemorySegment, TimeEntry<MemorySegment>> dao;
    public static final Logger log = Logger.getLogger(ServerDaoStrategy.class.getName());

    public ServerDaoStrategy(DaoServerConfig config) throws IOException {
        dao = new DaoImpl<>(
                mapConfig(config),
                SSTimeTableManager::new
        );
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        switch (Paths.getPathOrThrow(request.getPath())) {
            case V0_ENTITY -> {
                String entityId = Parameters.getParameter(request, Parameters.ID);
                MemorySegment key = fromString(entityId);
                return switch (request.getMethod()) {
                    case METHOD_GET -> getEntity(key);
                    case METHOD_PUT -> createEntity(key, MemorySegment.ofArray(request.getBody()));
                    case METHOD_DELETE -> deleteEntity(key);
                    default -> throw createIllegalState();
                };
            }
            case V0_ENTITIES -> {
                MemorySegment startKey = fromString(Parameters.getParameter(request, Parameters.START));
                MemorySegment endKey = fromString(Parameters.getParameter(request, Parameters.END));
                Iterator<TimeEntry<MemorySegment>> iterator = getEntities(startKey, endKey);
                if (iterator.hasNext()) {
                    byte[] kek = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                    session.write(kek, 0, kek.length);
                    session.write(new CustomQueueItem(
                            new ShiftedIterator<>(
                                new ApplyIterator<>(
                                        iterator,
                                        ServerDaoStrategy::mapEntry
                                )
                            )
                    ));
                    byte[] kek2 = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
                    session.write(kek2, 0, kek2.length);
                } else {
                    return Responses.OK.toResponse();
                }
                return null;
            }
            default -> throw createIllegalState();
        }
    }

    @Override
    public void close() throws IOException {
        dao.close();
    }

    private Response getEntity(MemorySegment key) {
        TimeEntry<MemorySegment> entry = dao.get(key);
        Response response;
        if (entry == null || entry.value() == null) {
            response = Responses.NOT_FOUND.toResponse();
        } else {
            response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
        }
        return addTimestamp(response, entry);
    }

    private Iterator<TimeEntry<MemorySegment>> getEntities(MemorySegment start, MemorySegment end) {
        return dao.get(start, end);
    }

    private Response upsertEntry(
            MemorySegment key,
            MemorySegment value,
            Responses responseBase
    ) {
        TimeEntry<MemorySegment> entry = makeEntry(key, value);
        dao.upsert(entry);
        return addTimestamp(responseBase.toResponse(), entry);
    }

    private Response createEntity(MemorySegment key, MemorySegment value) {
        return upsertEntry(key, value, Responses.CREATED);
    }

    private Response deleteEntity(MemorySegment key) {
        return upsertEntry(key, null, Responses.ACCEPTED);
    }

    private static Response addTimestamp(Response response, TimeEntry<?> entry) {
        if (entry != null) {
            Headers.addHeader(response, Headers.TIMESTAMP, entry.timestamp());
        }
        return response;
    }

    private static Config mapConfig(DaoServerConfig config) {
        return new Config(
                config.basePath,
                config.flushThresholdBytes
        );
    }

    private static TimeEntry<MemorySegment> makeEntry(MemorySegment key, MemorySegment value) {
        return new MSegmentTimeEntry(key, value, Instant.now().toEpochMilli());
    }

    private static MemorySegment fromString(final String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(CHARSET));
    }

    private static byte[] CHUNK_LINE_END = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static byte[] KEY_VALUE_SEP = "\n".getBytes(StandardCharsets.UTF_8);

    private static byte[] mapEntry(DaoEntry<MemorySegment> entry) {
        int keySize = (int)entry.key().byteSize();
        int valueSize = entry.value() == null ? 0 : (int)entry.value().byteSize();
        int totalSize = (int)(keySize + valueSize + KEY_VALUE_SEP.length);
        byte[] sizeBytes = Integer.toHexString(totalSize).getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[totalSize + sizeBytes.length + 2 * CHUNK_LINE_END.length];
        System.arraycopy(sizeBytes, 0, bytes, 0, sizeBytes.length);
        System.arraycopy(CHUNK_LINE_END, 0, bytes, sizeBytes.length, CHUNK_LINE_END.length);
        int offset = sizeBytes.length + CHUNK_LINE_END.length;
        segmentCopy(entry.key(), 0, bytes, offset, keySize);
        offset += keySize;
        System.arraycopy(KEY_VALUE_SEP, 0, bytes, offset, KEY_VALUE_SEP.length);
        offset += KEY_VALUE_SEP.length;
        if (entry.value() != null) {
            segmentCopy(entry.value(), 0, bytes, offset, valueSize);
            offset += valueSize;
        }
        System.arraycopy(CHUNK_LINE_END, 0, bytes, offset, CHUNK_LINE_END.length);

        return bytes;
    }

    private static void segmentCopy(MemorySegment src, long srcOffset, byte[] dst, int dstOffset, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length should be positive");
        }
        if (dstOffset > dst.length - length) {
            throw new IllegalArgumentException("Dst not enough size");
        }
        for (int i = 0; i < length; i++) {
            dst[dstOffset + i] = src.get(ValueLayout.JAVA_BYTE, srcOffset + i);
        }
    }
}
