package ru.vk.itmo.test.kislovdanil.service.sharding;

import java.net.http.HttpClient;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

public class RandevouzSharder extends ReplicativeBaseSharder {
    private final List<String> urls;
    private final Map<String, Integer> urlsHash = new HashMap<>();

    public RandevouzSharder(HttpClient client, List<String> urls) {
        super(client);
        this.urls = urls;
        final Random random = new Random(urls.getFirst().hashCode());
        for (String url : urls) {
            this.urlsHash.put(url, random.nextInt(100000));
        }
    }

    private class HashComparator implements Comparator<String> {
        private final String key;

        public HashComparator(String key) {
            this.key = key;
        }

        @Override
        public int compare(String o1, String o2) {
            return Integer.compare(getHash(o1, key), getHash(o2, key));
        }
    }

    // Attempt to make hashing more uniform distributed rather than built-in Object.hash
    private int getHash(String url, String key) {
        return (urlsHash.get(url) ^ key.hashCode()) % (urls.size() * 5);
    }

    @Override
    public List<String> defineRequestProxyUrls(String entityKey, int from) {
        // Looks nice, I just hope it won't consume too much memory...
        return urls.stream().sorted(new HashComparator(entityKey)).limit(from).collect(Collectors.toList());
    }
}
