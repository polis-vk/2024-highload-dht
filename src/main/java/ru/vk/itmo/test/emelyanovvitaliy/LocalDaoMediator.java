package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.Request;
import ru.vk.itmo.test.emelyanovvitaliy.dao.ReferenceDao;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalDaoMediator extends DaoMediator {
    protected final ReferenceDao dao;
    protected final AtomicBoolean isClosed = new AtomicBoolean(false);

    LocalDaoMediator(ReferenceDao dao) {
        this.dao = dao;
    }

    @Override
    void stop() {
        if (isClosed.getAndSet(true)) {
            return;
        }
        try {
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    boolean isStopped() {
        return isClosed.get();
    }

    @Override
    boolean put(Request request) {
        String id = request.getParameter(DhtServer.ID_KEY);
        dao.upsert(new TimestampedEntry<>(keyFor(id), MemorySegment.ofArray(request.getBody())));
        return true;
    }

    @Override
    TimestampedEntry<MemorySegment> get(Request request) {
        MemorySegment id = keyFor(request.getParameter(DhtServer.ID_KEY));
        TimestampedEntry<MemorySegment> entry = dao.get(id);
        if (entry == null) {
            return new TimestampedEntry<>(id, null, NEVER_TIMESTAMP);
        }
        return entry;
    }

    @Override
    boolean delete(Request request) {
        String id = request.getParameter(DhtServer.ID_KEY);
        dao.upsert(new TimestampedEntry<>(keyFor(id), null));
        return true;
    }
}
