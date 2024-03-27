package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.util.Hash;
import org.apache.log4j.Logger;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.shishiginstepan.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

public class DistributedDao implements Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> {

    private final Logger logger = Logger.getLogger("lsm-db-server");
    private static final int MULTIPLICATION_FACTOR = 128;
    private final NodeWrapper localDao;
    private final SortedMap<Integer, NodeWrapper> nodeRing = new ConcurrentSkipListMap<>();

    private final List<NodeWrapper> nodesUnique = new ArrayList<>();

    public DistributedDao(Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> localDao, String selfUrl) {
        this.localDao = new NodeWrapper(localDao, selfUrl);
    }

    private static final class NotEnoughUniqueNodes extends RuntimeException {
    }

    public static final class NoConsensus extends RuntimeException {
    }

    public static final class ClusterLimitExceeded extends RuntimeException {
    }

    private int totalNodes = 0;
    private int quorum = 0;

    private static class NodeWrapper implements Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> {
        Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> dao;
        String realNodeKey;

        public NodeWrapper(Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> dao, String key) {
            this.dao = dao;
            this.realNodeKey = key;
        }

        @Override
        public Iterator<EntryWithTimestamp<MemorySegment>> get(MemorySegment from, MemorySegment to) {
            return dao.get(from, to);
        }

        @Override
        public EntryWithTimestamp<MemorySegment> get(MemorySegment key) {
            return dao.get(key);
        }

        @Override
        public void upsert(EntryWithTimestamp<MemorySegment> entry) {
            dao.upsert(entry);
        }

        @Override
        public void close() throws IOException {
            dao.close();
        }
    }

    public void addNode(Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> daoNode, String token) {
        NodeWrapper wrapper = new NodeWrapper(daoNode, token);
        nodesUnique.add(wrapper);
        for (int i = 0; i < MULTIPLICATION_FACTOR; i++) {
            nodeRing.put(
                    Hash.murmur3((token + i)),
                    wrapper
            );
        }
        totalNodes++;
        quorum = (totalNodes / 2) + 1;
    }

    // Здесь мы будем итерироваться по кольцу и выбирать ноды, причем не просто первые N, а первые N которые памятся на уникальные реальные ноды
    private List<NodeWrapper> selectMultipleNodes(String key, int numberOfNodes) {
        int keyHash = Hash.murmur3(key);
        List<NodeWrapper> chosenNodes = new ArrayList<>(numberOfNodes);
        SortedMap<Integer, NodeWrapper> ringPart = this.nodeRing.tailMap(keyHash);
        Set<String> tokensOfChosenNodes = new HashSet<>();
        for (NodeWrapper node : ringPart.values()) {
            if (numberOfNodes == 0) {
                break;
            }
            if (tokensOfChosenNodes.contains(node.realNodeKey)) {
                continue;
            }
            chosenNodes.add(node);
            tokensOfChosenNodes.add(node.realNodeKey);
            numberOfNodes--;
        } // здесь мы можем дойти до конца мапы которая является кольцом нод. если мы до сих пор не набрали нужное кол-во нод, нужно теперь посмотреть другую часть кольца.
        ringPart = this.nodeRing.headMap(keyHash);
        for (NodeWrapper node : ringPart.values()) {
            if (numberOfNodes == 0) {
                break;
            }
            if (tokensOfChosenNodes.contains(node.realNodeKey)) {
                continue;
            }
            chosenNodes.add(node);
            tokensOfChosenNodes.add(node.realNodeKey);
            numberOfNodes--;
        }

        if (numberOfNodes > 0) {
            throw new NotEnoughUniqueNodes();
        }

        return chosenNodes;
    }

    @Override
    public Iterator<EntryWithTimestamp<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return null;
    }

    @Override
    public EntryWithTimestamp<MemorySegment> get(MemorySegment key) {
        return localDao.get(key);
    }

    @Override
    public void upsert(EntryWithTimestamp<MemorySegment> entry) {
        localDao.upsert(entry);
    }

    @Override
    public void close() throws IOException {
        for (Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> dao : nodesUnique) {
            dao.close();
        }
    }

    public EntryWithTimestamp<MemorySegment> get(MemorySegment key, Integer from, Integer ack) {
        if (ack == null) {
            ack = quorum;
        }
        if (from == null) {
            from = totalNodes;
        }
        if (ack > totalNodes || from > totalNodes || ack == 0 || from == 0) {
            throw new ClusterLimitExceeded();
        }
        List<NodeWrapper> nodesToPoll =
                selectMultipleNodes(
                        new String(key.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8),
                        from
                );
        PriorityQueue<EntryWithTimestamp<MemorySegment>> entries = new PriorityQueue<>(
                from,
                (e1, e2) -> -e1.timestamp().compareTo(e2.timestamp())
        );

        for (var node : nodesToPoll) {
            try {
                entries.add(
                        node.get(key)
                );
            } catch (Exception e) {
                logger.error(e);
            }
        }
        if (entries.size() < ack) {
            throw new NoConsensus();
        }
        return entries.peek();
    }

    public void upsert(EntryWithTimestamp<MemorySegment> entry, Integer from, Integer ack) {
        if (ack == null) {
            ack = quorum;
        }
        if (from == null) {
            from = totalNodes;
        }
        if (ack > totalNodes || from > totalNodes || ack == 0 || from == 0) {
            throw new ClusterLimitExceeded();
        }
        List<NodeWrapper> nodesToPoll =
                selectMultipleNodes(
                        new String(entry.key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8),
                        from
                );
        Integer n = 0;

        for (var node : nodesToPoll) {
            try {
                node.upsert(entry);
                n++;
            } catch (Exception e) {
                logger.error(e);
            }
        }
        if (n < ack) {
            throw new NoConsensus();
        }
    }
}
