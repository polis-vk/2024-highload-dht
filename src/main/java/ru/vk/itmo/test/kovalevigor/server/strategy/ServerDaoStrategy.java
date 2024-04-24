package ru.vk.itmo.test.kovalevigor.server.strategy;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.Session;
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
import ru.vk.itmo.test.kovalevigor.server.strategy.util.BaseByteStorage;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.ByteStorage;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.ChainedQueueItem;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.Headers;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.JoinedQueueItem;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.MemorySegmentByteStorage;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.Parameters;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.Paths;
import ru.vk.itmo.test.kovalevigor.server.strategy.util.Responses;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;
import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.createIllegalState;

public class ServerDaoStrategy extends ServerRejectStrategy {
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private static final byte[] RANGE_HEADER = """
            HTTP/1.1 200 OK\r
            Content-Type: text/plain\r
            Transfer-Encoding: chunked\r
            Connection: keep-alive\r
            \r
            """.getBytes(CHARSET);
    private static final byte[] RANGE_END = "0\r\n\r\n".getBytes(CHARSET);
    private static final ByteStorage CHUNK_LINE_END = new BaseByteStorage("\r\n".getBytes(CHARSET));
    private static final ByteStorage KEY_VALUE_SEP = new BaseByteStorage("\n".getBytes(CHARSET));

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
                    session.write(
                            new ChainedQueueItem(
                                    List.of(
                                        new Session.ArrayQueueItem(
                                                RANGE_HEADER,
                                                0,
                                                RANGE_HEADER.length,
                                                0
                                        ),
                                        new ChainedQueueItem(
                                                new ShiftedIterator<>(
                                                        new ApplyIterator<>(
                                                                iterator,
                                                                ServerDaoStrategy::mapEntry
                                                        )
                                                )
                                        ),
                                        new Session.ArrayQueueItem(
                                                RANGE_END,
                                                0,
                                                RANGE_END.length,
                                                0
                                        )
                                    ).iterator()
                            )
                    );
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

    private static Session.QueueItem mapEntry(DaoEntry<MemorySegment> entry) {
        int keySize = (int)entry.key().byteSize();
        int valueSize = entry.value() == null ? 0 : (int)entry.value().byteSize();
        long totalSize = keySize + valueSize + KEY_VALUE_SEP.size();
        return new JoinedQueueItem(
                1024,
                List.of(
                        new BaseByteStorage(Long.toHexString(totalSize).getBytes(CHARSET)),
                        CHUNK_LINE_END,
                        new MemorySegmentByteStorage(entry.key()),
                        KEY_VALUE_SEP,
                        new MemorySegmentByteStorage(entry.value()),
                        CHUNK_LINE_END
                ).iterator()
        );
    }
}
