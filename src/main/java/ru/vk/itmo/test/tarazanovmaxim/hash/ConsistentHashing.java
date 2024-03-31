package ru.vk.itmo.test.tarazanovmaxim.hash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

public class ConsistentHashing {
    private final NavigableMap<Integer, String> ring = new TreeMap<>();
    private final Hasher hasher = new Hasher();

    private int hashKey(String key) {
        return hasher.digest(key.getBytes(StandardCharsets.UTF_8));
    }

    public String getShardByKey(String key) {
        final Map.Entry<Integer, String> ent = ring.ceilingEntry(hashKey(key));
        if (ent == null) {
            return ring.firstEntry().getValue();
        } else {
            return ent.getValue();
        }
    }

    public List<String> getNShardByKey(final String key, final int n) {
        /*
        final Set<String> ret = new HashSet<>(n);
        ret.add(getShardByKey(key));
        int i = 0;
        while (ret.size() < n) {
            final String candidate = getShardByKey(key + i);
            ret.add(candidate);
            i++;
        }
        */
        String curShard = getShardByKey(key);
        List<String> shards = ring.values().stream().toList();
        List<String> retShards = new ArrayList<>();
        int curpos = 0;
        for (; curpos < ring.size(); ++curpos) {
            if (shards.get(curpos).equals(curShard)) {
                for (int i = 0; i < n; ++i) {
                    if (curpos == shards.size()) {
                        curpos = 0;
                    }
                    retShards.addLast(shards.get(curpos));
                    ++curpos;
                }
            }
        }

        return List.copyOf(ring.values());
        //return List.copyOf(new HashSet<>(ring.values()));
    }

    public void addShard(String newShard, Set<Integer> nodeHashes) {
        for (final int nodeHash : nodeHashes) {
            ring.putIfAbsent(nodeHash, newShard);
        }
    }
}
