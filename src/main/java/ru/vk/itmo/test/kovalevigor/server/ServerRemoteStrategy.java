package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;

import java.io.IOException;
import java.util.logging.Level;

import static ru.vk.itmo.test.kovalevigor.server.ServerDaoStrategy.log;

public class ServerRemoteStrategy extends ServerRejectStrategy {
    public static final int REMOTE_TIMEOUT = 1000;

    private final HttpClient remoteClient;

    public ServerRemoteStrategy(String remoteUrl) {
        this.remoteClient = new HttpClient(new ConnectionString(remoteUrl));
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            Response response = remoteClient.invoke(new Request(request), REMOTE_TIMEOUT);
            session.sendResponse(new Response(response));
        } catch (InterruptedException | HttpException | PoolException e) {
            log.log(Level.SEVERE, "Exception while redirection", e);
            session.sendError(Response.SERVICE_UNAVAILABLE, null);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() throws IOException {
        remoteClient.close();
    }
}
