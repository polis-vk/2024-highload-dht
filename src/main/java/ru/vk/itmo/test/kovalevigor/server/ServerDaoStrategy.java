package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;
import ru.vk.itmo.test.kovalevigor.dao.DaoImpl;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;
import static ru.vk.itmo.test.kovalevigor.server.ServerUtil.sendResponseWithoutIo;

public class ServerDaoStrategy implements ServerStrategy {

    private static final String ENTITY = "/v0/entity";
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    public static final Logger log = Logger.getLogger(ServerDaoStrategy.class.getName());

    public ServerDaoStrategy(DaoServerConfig config) throws IOException {
        dao = new DaoImpl(mapConfig(config));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        if (request.getPath().equals(ENTITY)) {
            switch (request.getMethod()) {
                case METHOD_GET:
                case METHOD_PUT:
                case METHOD_DELETE:
                    String entityId = request.getParameter("id=");
                    if (entityId == null || entityId.isEmpty()) {
                        break;
                    }
                    MemorySegment key = fromString(entityId);
                    session.sendResponse(
                            switch (request.getMethod()) {
                                case METHOD_GET -> getEntity(key);
                                case METHOD_PUT -> createEntity(key, MemorySegment.ofArray(request.getBody()));
                                case METHOD_DELETE -> deleteEntity(key);
                                default -> throw new IllegalStateException("Can't be");
                            }
                    );
                    return;
                default:
                    session.sendResponse(Responses.NOT_ALLOWED.toResponse());
                    return;
            }
        }
        session.sendResponse(Responses.BAD_REQUEST.toResponse());
    }

    @Override
    public void rejectRequest(Request request, HttpSession session) {
        sendResponseWithoutIo(session, Responses.SERVICE_UNAVAILABLE);
    }

    @Override
    public void close() throws IOException {
        dao.close();
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(Responses.BAD_REQUEST.toResponse());
    }

    private Response getEntity(MemorySegment key) {
        Entry<MemorySegment> entity = dao.get(key);
        if (entity == null) {
            return Responses.NOT_FOUND.toResponse();
        }
        return Response.ok(entity.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response createEntity(MemorySegment key, MemorySegment value) {
        dao.upsert(makeEntry(key, value));
        return Responses.CREATED.toResponse();
    }

    private Response deleteEntity(MemorySegment key) {
        dao.upsert(makeEntry(key, null));
        return Responses.ACCEPTED.toResponse();
    }

    private static Config mapConfig(DaoServerConfig config) {
        return new Config(
                config.basePath,
                config.flushThresholdBytes
        );
    }

    private static Entry<MemorySegment> makeEntry(MemorySegment key, MemorySegment value) {
        return new BaseEntry<>(key, value);
    }

    private static MemorySegment fromString(final String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(CHARSET));
    }
}
