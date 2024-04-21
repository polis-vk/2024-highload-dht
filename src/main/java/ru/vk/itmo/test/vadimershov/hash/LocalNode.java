package ru.vk.itmo.test.vadimershov.hash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.vadimershov.ResultResponse;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;

import java.lang.foreign.MemorySegment;
import java.net.HttpURLConnection;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

import static ru.vk.itmo.test.vadimershov.utils.MSUtil.toByteArray;
import static ru.vk.itmo.test.vadimershov.utils.MSUtil.toDeletedEntity;
import static ru.vk.itmo.test.vadimershov.utils.MSUtil.toEntity;
import static ru.vk.itmo.test.vadimershov.utils.MSUtil.toMemorySegment;

public class LocalNode extends VirtualNode {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> dao;

    public LocalNode(String url, Dao<MemorySegment, TimestampEntry<MemorySegment>> dao, int replicaIndex) {
        super(url, replicaIndex);
        this.dao = dao;
    }

    @Override
    public void close() {
        /* ничего не делаем так как закрываем локальную бд выше по иерархии */
    }

    @Override
    public CompletableFuture<ResultResponse> get(String key) {
        return CompletableFuture.supplyAsync(() -> {
            TimestampEntry<MemorySegment> entry;
            try {
                entry = dao.get(toMemorySegment(key));
            } catch (Exception e) {
                logger.error("Can't get value by key={}", key, e);
                return new ResultResponse(HttpURLConnection.HTTP_INTERNAL_ERROR, null, 0L);
            }
            if (entry.value() == null) {
                long timestamp = entry.timestamp() == null ? 0L : entry.timestamp();
                return new ResultResponse(HttpURLConnection.HTTP_NOT_FOUND, null, timestamp);
            }

            return new ResultResponse(HttpURLConnection.HTTP_OK, toByteArray(entry.value()), entry.timestamp());
        });
    }

    @Override
    public CompletableFuture<ResultResponse> upsert(String key, byte[] value, Long timestamp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                dao.upsert(toEntity(key, value, timestamp));
            } catch (Exception e) {
                logger.error("Can't upsert value by key={}", key, e);
                return new ResultResponse(HttpURLConnection.HTTP_INTERNAL_ERROR, null, 0L);
            }
            return new ResultResponse(HttpURLConnection.HTTP_CREATED, null, 0L);
        });
    }

    @Override
    public CompletableFuture<ResultResponse> delete(String key, Long timestamp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                dao.upsert(toDeletedEntity(key, timestamp));
            } catch (Exception e) {
                logger.error("Can't upsert value by key={}", key, e);
                return new ResultResponse(HttpURLConnection.HTTP_INTERNAL_ERROR, null, 0L);
            }
            return new ResultResponse(HttpURLConnection.HTTP_ACCEPTED, null, 0L);
        });
    }

    @Override
    public Iterator<TimestampEntry<MemorySegment>> range(String start, String end) {
        return dao.get(toMemorySegment(start), end == null ? null : toMemorySegment(end));
    }
}
