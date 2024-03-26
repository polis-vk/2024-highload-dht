package ru.vk.itmo.test.kovalevigor.server;

import one.nio.server.SelectorThread;
import one.nio.server.ServerConfig;
import one.nio.server.ServerMXBean;

import java.io.IOException;

public interface ServerFull extends ServerMXBean, AutoCloseable {
    void reconfigure(ServerConfig config) throws IOException;

    void start();

    void stop();

    @Override
    void close() throws IOException;

    SelectorThread[] getSelectors();
}
