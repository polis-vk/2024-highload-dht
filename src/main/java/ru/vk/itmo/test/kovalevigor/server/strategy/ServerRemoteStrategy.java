package ru.vk.itmo.test.kovalevigor.server.strategy;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;

import java.io.IOException;
import java.net.ConnectException;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;

public class ServerRemoteStrategy extends ServerRejectStrategy {
    public static final int REMOTE_TIMEOUT = 1000;

    private final HttpClient remoteClient;

    public ServerRemoteStrategy(String remoteUrl) {
        this.remoteClient = new HttpClient(new ConnectionString(remoteUrl));
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        try {
            Response response = remoteClient.invoke(new Request(request), REMOTE_TIMEOUT);
            return new Response(response);
        } catch (HttpException | PoolException | ConnectException e) {
            log.severe("Exception while redirection");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        remoteClient.close();
    }
}
