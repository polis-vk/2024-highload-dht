package ru.vk.itmo.test.kislovdanil.service.sharding;

import java.net.http.HttpClient;
import java.util.List;

public class RandevouzSharder extends BaseSharder {
    private final List<String> urls;

    public RandevouzSharder(HttpClient client, List<String> urls) {
        super(client);
        this.urls = urls;
    }
    @Override
    public String defineRequestProxyUrl(String entityKey) {
        int maxHash = Integer.MIN_VALUE;
        String maxHashUrl = null;
        for (String url: urls) {
            int hash = (url + entityKey).hashCode();
            if (hash >= maxHash) {
                maxHash = hash;
                maxHashUrl = url;
            }
        }
        return maxHashUrl;
    }
}
