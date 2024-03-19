package ru.vk.itmo.test.kislovdanil.service.sharding;

import ru.vk.itmo.test.kislovdanil.service.InvalidTopologyError;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RandevouzSharder extends BaseSharder {
    private final List<String> urls;
    private final List<Integer> urlsHash = new ArrayList<>();

    public RandevouzSharder(HttpClient client, List<String> urls) {
        super(client);
        this.urls = urls;
        final Random random = new Random(urls.getFirst().hashCode());
        for (int i = 0; i < urls.size(); i++) {
            urlsHash.add(random.nextInt(100000));
        }
    }
    // Attempt to make hashing more uniform distributed rather than built-in Object.hash
    private int getHash(int urlIndex, String key) {
        return (urlsHash.get(urlIndex) ^ key.hashCode()) % (urls.size() * 5);
    }

    @Override
    public String defineRequestProxyUrl(String entityKey) {
        int maxHash = Integer.MIN_VALUE;
        String maxHashUrl = null;
        for (int i = 0; i < urls.size(); i++) {
            int hash = getHash(i, entityKey);
            if (hash >= maxHash) {
                maxHash = hash;
                maxHashUrl = urls.get(i);
            }
        }
        if (maxHashUrl == null) {
            throw new InvalidTopologyError();
        }
        return maxHashUrl;
    }
}
