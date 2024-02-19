package ru.vk.itmo.test.kachmareugene;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class Server extends HttpServer {

    public Server(ServiceConfig conf) throws IOException {
        super(convertToHttpConfig(conf));
    }

    private static HttpServerConfig convertToHttpConfig(ServiceConfig conf) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = conf.selfPort();
        acceptorConfig.address = conf.selfUrl();
        acceptorConfig.reusePort = true;

        HttpServerConfig httpServerConfig = new HttpServerConfig();
        httpServerConfig.closeSessions = true;
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        return httpServerConfig;
    }
}
