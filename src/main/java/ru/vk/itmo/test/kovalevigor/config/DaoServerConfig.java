package ru.vk.itmo.test.kovalevigor.config;

import one.nio.config.Config;
import one.nio.http.HttpServerConfig;

import java.nio.file.Path;
import java.util.List;

@Config
public class DaoServerConfig extends HttpServerConfig {
    public Path basePath;
    public long flushThresholdBytes;
    public int corePoolSize = 2;
    public int maximumPoolSize = 8;
    public long keepAliveTime = 100;
    public int queueCapacity = 500;
    public List<String> clusterUrls;
    public String selfUrl;
}
