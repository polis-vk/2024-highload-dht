package ru.vk.itmo.test.kislovdanil.service.sharding;

import ru.vk.itmo.test.kislovdanil.service.InvalidTopologyError;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class RandevouzSharder extends BaseSharder {
    private final List<String> urls;
    private final Map<String, Integer> urlsHash = new HashMap<>();

    public RandevouzSharder(HttpClient client, List<String> urls) {
        super(client);
        this.urls = urls;
        final Random random = new Random(urls.getFirst().hashCode());
        for (String url: urls) {
            urlsHash.put(url, random.nextInt(100000));
        }
    }

    // Attempt to make hashing more uniform distributed rather than built-in Object.hash
    private int getHash(String url, String key) {
        return (urlsHash.get(url) ^ key.hashCode()) % (urls.size() * 5);
    }

    @Override
    public String defineRequestProxyUrl(String entityKey) {
        int maxHash = Integer.MIN_VALUE;
        String maxHashUrl = null;
        for (String url: urls) {
            int hash = getHash(url, entityKey);
            if (hash >= maxHash) {
                maxHash = hash;
                maxHashUrl = url;
            }
        }
        if (maxHashUrl == null) {
            throw new InvalidTopologyError();
        }
        return maxHashUrl;
    }
}
