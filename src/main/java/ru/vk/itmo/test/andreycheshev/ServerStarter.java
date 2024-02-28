package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServerConfig;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.dao.Config;

import java.io.IOException;
import java.nio.file.Path;

public final class ServerStarter {
    private static final Path DIT_PATH = Path.of("/home/andrey/andrey/tmp");
    private static final int THRESHOLD_BYTES = 100000;
    private static final int PORT = 8080;

    private ServerStarter() {

    }

    public static void main(String[] args) throws IOException {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = PORT;
        acceptorConfig.reusePort = true;
        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        Config daoConfig = new Config(DIT_PATH, THRESHOLD_BYTES);

        ServerImpl server = new ServerImpl(serverConfig, daoConfig);

        server.start();
    }
}
