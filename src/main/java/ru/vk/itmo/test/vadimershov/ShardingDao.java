package ru.vk.itmo.test.vadimershov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.vadimershov.dao.ReferenceDao;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.FailedSharding;
import ru.vk.itmo.test.vadimershov.exceptions.NotFoundException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;
import ru.vk.itmo.test.vadimershov.hash.ConsistentHashing;
import ru.vk.itmo.test.vadimershov.hash.VirtualNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.PriorityQueue;

public class ShardingDao {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final int nodeCount;
    private final int nodeQuorum;

    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> localDao;
    private final ConsistentHashing consistentHashing;

    public ShardingDao(ServiceConfig serviceConfig, Config daoConfig) throws IOException {
        this.nodeCount = serviceConfig.clusterUrls().size();
        this.nodeQuorum = (serviceConfig.clusterUrls().size() / 2) + 1;
        this.localDao = new ReferenceDao(daoConfig);
        this.consistentHashing = new ConsistentHashing(serviceConfig.selfUrl(), serviceConfig.clusterUrls(), this.localDao);
    }

    public Pair<byte[], Long> get(String key) throws NotFoundException, DaoException, RemoteServiceException {
        return consistentHashing.getLocalNode().get(key);
    }

    public Pair<byte[], Long> get(
            String key,
            Integer ack,
            Integer from
    ) throws NotFoundException, DaoException, RemoteServiceException, FailedSharding {
        ack = validate(ack, nodeQuorum);
        from = validate(from, nodeCount);

        Collection<VirtualNode> vNodes = consistentHashing.findVNodes(key, from);
        PriorityQueue<Pair<byte[], Long>> entityQueue =
                new PriorityQueue<>(from, (e1, e2) -> - e1.second().compareTo(e2.second()));
        for (var node : vNodes) {
            try {
                entityQueue.add(node.get(key));
            } catch (DaoException | RemoteServiceException e) {
                logger.error("Exception with remote node in from param", e);
//                throw new FailedSharding();
            }
        }
        if (entityQueue.size() < ack) {
            throw new FailedSharding();
        }
        return entityQueue.poll();
    }

    public void upsert(String key, byte[] value, Long timestamp) throws DaoException, RemoteServiceException, FailedSharding {
        consistentHashing.getLocalNode().upsert(key, value, timestamp);
    }

    public void upsert(
            String key,
            byte[] value,
            Integer ack,
            Integer from
    ) throws DaoException, RemoteServiceException {
        ack = validate(ack, nodeQuorum);
        from = validate(from, nodeCount);

        Collection<VirtualNode> vNodes = consistentHashing.findVNodes(key, from);
        int correctSave = 0;
        long timestamp = System.currentTimeMillis();
        for (var node : vNodes) {
            try {
                logger.info("" + timestamp);
                node.upsert(key, value, timestamp);
                correctSave++;
            } catch (Exception e) {
                logger.error("Exception with remote node in from param", e);
            }
        }
        if (correctSave < ack) {
            throw new FailedSharding();
        }
    }

    public void delete(String key, Long timestamp) throws DaoException, RemoteServiceException {
        consistentHashing.getLocalNode().delete(key, timestamp);
    }

    public void delete(
            String key,
            Integer ack,
            Integer from
    ) throws DaoException, RemoteServiceException, FailedSharding {
        ack = validate(ack, nodeQuorum);
        from = validate(from, nodeCount);

        Collection<VirtualNode> vNodes = consistentHashing.findVNodes(key, from);
        int correctSave = 0;
        long timestamp = System.currentTimeMillis();
        for (var node : vNodes) {
            try {
                node.delete(key, timestamp);
                correctSave++;
            } catch (Exception e) {
                logger.error("Exception with remote node in from param", e);
            }
        }
        if (correctSave < ack) {
            throw new FailedSharding();
        }
    }

    public void close() {
        try {
            this.localDao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.consistentHashing.close();
    }

    private int validate(Integer value, Integer defaultValue) {
        value = value == null ? defaultValue : value;

        if (value > nodeCount  || value <= 0 ) {
            throw new FailedSharding(DaoResponse.BAD_REQUEST);
        }
        return value;
    }

}
