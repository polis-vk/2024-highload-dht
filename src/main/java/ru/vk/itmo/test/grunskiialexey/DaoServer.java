package ru.vk.itmo.test.grunskiialexey;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.Path;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class DaoServer extends HttpServer {
    public DaoServer(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public static void main(String[] args) throws IOException {
        DaoServer server = new DaoServer(new ServiceConfig(
                8080, "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")
        ));
        server.start();
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }
}
