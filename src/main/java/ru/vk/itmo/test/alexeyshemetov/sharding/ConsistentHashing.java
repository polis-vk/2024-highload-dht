package ru.vk.itmo.test.alexeyshemetov.sharding;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

public class ConsistentHashing implements ShardingManager {
    private final Function<String, Integer> hashFunction;
    private final int[] vnodeHashes;
    private final String[] vnodeUrls;

    public ConsistentHashing(List<String> clusterUrls, int vnodesPerCluster,
                                    Function<String, Integer> hashFunction) {
        this.hashFunction = hashFunction;

        Map<Integer, String> vnodesMap = makeVnodesMap(clusterUrls, vnodesPerCluster, hashFunction);

        vnodeHashes = new int[vnodesMap.size()];
        vnodeUrls = new String[vnodesMap.size()];

        int index = 0;
        for (var entry : vnodesMap.entrySet()) {
            vnodeHashes[index] = entry.getKey();
            vnodeUrls[index] = entry.getValue();
            ++index;
        }
    }

    private static Map<Integer, String> makeVnodesMap(List<String> clusterUrls, int vnodesPerCluster,
                                                      Function<String, Integer> hashFunction) {
        Map<Integer, String> vnodesMap = new TreeMap<>();
        for (String url : clusterUrls) {
            for (int index = 0; index < vnodesPerCluster; ++index) {
                int hash = hashFunction.apply(url + index);
                if (vnodesMap.containsKey(hash)) {
                    throw new RuntimeException("hash collision: choose another hash function");
                }
                vnodesMap.put(hash, url);
            }
        }
        return vnodesMap;
    }

    @Override
    public String getClusterUrlByKey(String key) {
        int hash = hashFunction.apply(key);
        int index = findNextHashIndex(hash);
        return vnodeUrls[index];
    }

    private int findNextHashIndex(int hash) {
        int left = -1;
        int right = vnodeHashes.length - 1;
        while (right - left > 1) {
            int mid = (left + right) >> 1;
            if (vnodeHashes[mid] < hash) {
                left = mid;
            } else {
                right = mid;
            }
        }
        return (right == vnodeHashes.length - 1 && vnodeHashes[right] < hash) ? 0 : right;
    }
}
