package ru.vk.itmo.test.dariasupriadkina.workers;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class WorkerConfig {

    public static final long KEEP_ALIVE_TIME = 1000L;
    public static final TimeUnit KEEP_ALIVE_TIME_SECONDS = TimeUnit.SECONDS;

    private final int corePoolSize;
    private final int maximumPoolSize;
    private final int shutdownTimeoutSec;
    private final ArrayBlockingQueue<Runnable> workQueue;

    public WorkerConfig(int corePoolSize, int maximumPoolSize, int queueSize, int shutdownTimeoutSec) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = new ArrayBlockingQueue<>(queueSize);
        this.shutdownTimeoutSec = shutdownTimeoutSec;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public BlockingQueue<Runnable> getWorkQueue() {
        return workQueue;
    }

    public int getShutdownTimeoutSec() {
        return shutdownTimeoutSec;
    }
}
