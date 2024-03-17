package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServerConfig;

import java.nio.file.Path;

public class DaoHttpServerConfig extends HttpServerConfig {
    public Path workingDir;
    public boolean useWorkers;
    public int queueSize;
    public int keepAliveTime;
    public boolean useVirtualThreads;
    public WorkerQueueType workerQueueType;
}
