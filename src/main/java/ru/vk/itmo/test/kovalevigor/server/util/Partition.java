package ru.vk.itmo.test.kovalevigor.server.util;

import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;

public final class Partition {
    public final ServerStrategy strategy;
    public final int start;
    public final int end;

    public Partition(ServerStrategy strategy, int start, int end) {
        this.strategy = strategy;
        this.start = start;
        this.end = end;
    }
}
