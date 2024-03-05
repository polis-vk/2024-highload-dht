package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServerConfig;

import java.nio.file.Path;

public class DaoHttpServerConfig extends HttpServerConfig {
    public Path workingDir;
}
