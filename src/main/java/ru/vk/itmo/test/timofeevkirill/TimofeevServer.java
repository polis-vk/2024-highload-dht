package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.Path;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;

public class TimofeevServer extends HttpServer {

    public TimofeevServer(ServiceConfig serviceConfig) throws IOException {
        super(createServerConfig(serviceConfig));
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        return serverConfig;
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }
}
