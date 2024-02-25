package ru.vk.itmo.test.trofimovmaxim;

public class ExecutorServiceSettings {
    static final int CORE_POOL_SIZE = 1;
    static final int MAX_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    static final long TIMEOUT_MS = 200;
    static final int QUEUE_SIZE = MAX_POOL_SIZE * 10;
}
