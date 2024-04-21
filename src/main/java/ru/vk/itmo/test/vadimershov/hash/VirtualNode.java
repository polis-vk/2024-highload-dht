package ru.vk.itmo.test.vadimershov.hash;

import ru.vk.itmo.test.vadimershov.ResultResponse;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;

public abstract class VirtualNode {
    protected final String url;
    protected final int replicaIndex;

    protected VirtualNode(String url, int replicaIndex) {
        this.url = url;
        this.replicaIndex = replicaIndex;
    }

    public String url() {
        return this.url;
    }

    public String key(String url) {
        return "Virtual node: " + url + "-" + this.replicaIndex;
    }

    public abstract void close();

    public abstract CompletableFuture<ResultResponse> get(String key);

    public abstract CompletableFuture<ResultResponse> upsert(String key, byte[] value, Long timestamp);

    public abstract CompletableFuture<ResultResponse> delete(String key, Long timestamp);

    public abstract Iterator<TimestampEntry<MemorySegment>> range(String start, String end);
}
