package ru.vk.itmo.test.trofimovmaxim;

public final class ExecutorServiceSettings {
    static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    static final int MAX_POOL_SIZE = CORE_POOL_SIZE;
    static final long TIMEOUT_MS = 200;
    static final int QUEUE_SIZE = MAX_POOL_SIZE * 10;

    private ExecutorServiceSettings() {
    }
}
