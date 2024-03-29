package ru.vk.itmo.test.smirnovandrew;

import one.nio.http.HttpClient;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.util.logging.Logger;

public final class MyServerUtil {
    private static final int CONNECTION_TIMEOUT = 1000;

    private MyServerUtil() {
    }

    public static HttpServerConfig generateServerConfig(ServiceConfig config) {
        var serverConfig = new HttpServerConfig();
        var acceptorsConfig = new AcceptorConfig();

        acceptorsConfig.port = config.selfPort();
        acceptorsConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorsConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public static HttpClient createClient(String url) {
        var client = new HttpClient(new ConnectionString(url));
        client.setConnectTimeout(CONNECTION_TIMEOUT);
        return client;
    }

    public static void sendEmpty(HttpSession session, Logger logger, String message) {
        try {
            session.sendResponse(new Response(message, Response.EMPTY));
        } catch (IOException e) {
            logger.info(e.getMessage());
        }
    }

    public static long headerTimestampToLong(Response r) {
        String header = r.getHeader("X-TimeStamp: ");
        if (header == null) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(header);
    }
}
