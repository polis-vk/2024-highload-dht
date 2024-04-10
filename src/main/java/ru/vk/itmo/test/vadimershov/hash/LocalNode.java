package ru.vk.itmo.test.vadimershov.hash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.vadimershov.ResultResponse;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;
import ru.vk.itmo.test.vadimershov.utils.MSUtil;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;

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
                entry = dao.get(MSUtil.toMemorySegment(key));
            } catch (Exception e) {
                logger.error("Can't get value by key={}", key, e);
                return new ResultResponse(HTTP_INTERNAL_ERROR, null, 0L);
            }
            logger.info(entry.toString());
            if (entry.value() == null) {
                return new ResultResponse(HTTP_NOT_FOUND, null, entry.timestamp() == null ? 0L : entry.timestamp());
            }

            return new ResultResponse(HTTP_OK, MSUtil.toByteArray(entry.value()), entry.timestamp());
        });
    }

    @Override
    public CompletableFuture<ResultResponse> upsert(String key, byte[] value, Long timestamp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                dao.upsert(MSUtil.toEntity(key, value, timestamp));
            } catch (Exception e) {
                logger.error("Can't upsert value by key={}", key, e);
                return new ResultResponse(HTTP_INTERNAL_ERROR, null, 0L);
            }
            return new ResultResponse(HTTP_CREATED, null, 0L);
        });
    }

    @Override
    public CompletableFuture<ResultResponse> delete(String key, Long timestamp) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                dao.upsert(MSUtil.toDeletedEntity(key, timestamp));
            } catch (Exception e) {
                logger.error("Can't upsert value by key={}", key, e);
                return new ResultResponse(HTTP_INTERNAL_ERROR, null, 0L);
            }
            return new ResultResponse(HTTP_ACCEPTED, null, 0L);
        });
    }
}
