package ru.vk.itmo.test;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;

public class ReferenceServer extends HttpServer {

    public ReferenceServer(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }
}
