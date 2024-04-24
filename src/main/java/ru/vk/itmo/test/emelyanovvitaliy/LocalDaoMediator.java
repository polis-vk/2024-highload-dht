package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.Request;
import ru.vk.itmo.test.emelyanovvitaliy.dao.ReferenceDao;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocalDaoMediator extends DaoMediator {
    protected final Executor executor;
    protected final ReferenceDao dao;
    protected final AtomicBoolean isClosed = new AtomicBoolean(false);

    LocalDaoMediator(ReferenceDao dao) {
        this.dao = dao;
        this.executor = Executors.newSingleThreadExecutor(); // stub for testing, don't use in production
    }

    LocalDaoMediator(ReferenceDao dao, Executor executor) {
        this.dao = dao;
        this.executor = executor;
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
    CompletableFuture<Boolean> put(Request request) {
        // upsert is a really fast operation, so as I think we can do it on main thread
        String id = request.getParameter(DhtServer.ID_KEY);
        dao.upsert(new TimestampedEntry<>(keyFor(id), MemorySegment.ofArray(request.getBody())));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    CompletableFuture<TimestampedEntry<MemorySegment>> get(Request request) {
        CompletableFuture<TimestampedEntry<MemorySegment>> ans = new CompletableFuture<>();
        executor.execute(() -> {
            MemorySegment id = keyFor(request.getParameter(DhtServer.ID_KEY));
            TimestampedEntry<MemorySegment> entry = dao.get(id);
            ans.complete(Objects.requireNonNullElseGet(entry, () -> new TimestampedEntry<>(id, null, NEVER_TIMESTAMP)));
        });
        return ans;
    }

    CompletableFuture<Iterator<TimestampedEntry<MemorySegment>>> getRange(Request request) {
        CompletableFuture<Iterator<TimestampedEntry<MemorySegment>>> ans = new CompletableFuture<>();
        executor.execute(() -> {
            MemorySegment start = keyFor(request.getParameter(DhtServer.START_KEY));
            String endKey = request.getParameter(DhtServer.END_KEY);
            MemorySegment end = null;
            if (endKey != null) {
                end = keyFor(endKey);
            }
            Iterator<TimestampedEntry<MemorySegment>> entries = dao.get(start, end);
            ans.complete(entries);
        });
        return ans;
    }

    @Override
    CompletableFuture<Boolean> delete(Request request) {
        String id = request.getParameter(DhtServer.ID_KEY);
        dao.upsert(new TimestampedEntry<>(keyFor(id), null));
        return CompletableFuture.completedFuture(true);
    }
}
