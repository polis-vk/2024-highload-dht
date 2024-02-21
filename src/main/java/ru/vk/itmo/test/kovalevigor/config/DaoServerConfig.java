package ru.vk.itmo.test.kovalevigor.config;

import one.nio.config.Config;
import one.nio.http.HttpServerConfig;

import java.nio.file.Path;

@Config
public class DaoServerConfig extends HttpServerConfig {
    public Path basePath;
    public long flushThresholdBytes;
}
