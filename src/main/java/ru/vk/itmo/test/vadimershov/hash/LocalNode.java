package ru.vk.itmo.test.vadimershov.hash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.vadimershov.Pair;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.NotFoundException;

import java.lang.foreign.MemorySegment;

import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toByteArray;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toDeletedEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toMemorySegment;

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
    public Pair<byte[], Long> get(String key) throws NotFoundException, DaoException {
        TimestampEntry<MemorySegment> entry;
        try {
            entry = dao.get(toMemorySegment(key));
        } catch (Exception e) {
            logger.error("Can't get value by key={}", key, e);
            throw new DaoException("Can't get value from local dao", e);
        }
        if (entry.value() == null) {
            return new Pair<>(null, entry.timestamp());
        }
        return new Pair<>(toByteArray(entry.value()), entry.timestamp());
    }

    @Override
    public void upsert(String key, byte[] value, Long timestamp) throws DaoException {
        try {
            dao.upsert(toEntity(key, value, timestamp));
        } catch (Exception e) {
            logger.error("Can't upsert value by key={}", key, e);
            throw new DaoException("Can't upsert value in local dao", e);
        }
    }

    @Override
    public void delete(String key, Long timestamp) throws DaoException {
        try {
            dao.upsert(toDeletedEntity(key, timestamp));
        } catch (Exception e) {
            logger.error("Can't delete by key={}", key, e);
            throw new DaoException("Can't delete value in local dao", e);
        }
    }
}
