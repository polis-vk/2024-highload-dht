package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServerConfig;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.dao.Config;

import java.io.IOException;
import java.nio.file.Path;

public final class ServerStarter {
    private static final Path dirPath = Path.of("/home/andrey/andrey/tmp");
    private static final int THRESHOLD_BYTES = 100000;

    private ServerStarter() {

    }

    public static void main(String[] args) throws IOException {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = 8080;
        acceptorConfig.reusePort = true;
        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        Config daoConfig = new Config(dirPath, THRESHOLD_BYTES);

        ServerImpl server = new ServerImpl(serverConfig, daoConfig);

        server.start();
    }
}
