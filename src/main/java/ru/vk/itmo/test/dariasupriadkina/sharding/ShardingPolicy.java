package ru.vk.itmo.test.dariasupriadkina.sharding;

public interface ShardingPolicy {

    String getUrlById(String id);
    void add(String node);
    void remove(String node);
    int hash(String str);
}
