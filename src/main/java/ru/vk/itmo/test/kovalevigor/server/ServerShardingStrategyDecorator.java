package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ServerShardingStrategyDecorator extends ServerStrategyDecorator {

    private final List<ServerStrategy> clusterClients;
    private final int clusterIndex;
    public ServerShardingStrategyDecorator(
            ServerStrategy httpServer,
            List<String> clusterUrls,
            String clusterUrl
    ) {
        super(httpServer);
        this.clusterClients = new ArrayList<>(clusterUrls.size());
        clusterUrls.forEach(url ->
            clusterClients.add(
                    url.equals(clusterUrl)
                    ? this
                    : new ServerRemoteStrategy(url)
            )
        );
        this.clusterIndex = clusterUrls.indexOf(clusterUrl);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        String key = request.getParameter("id=");
        if (key == null || key.isEmpty()) {
            super.handleDefault(request, session);
            return;
        }
        int keyClusterIndex = getClusterIndex(key);
        if (keyClusterIndex == clusterIndex) {
            super.handleRequest(request, session);
        } else {
            clusterClients.get(keyClusterIndex).handleRequest(request, session);
        }
    }

    private int getClusterIndex(String key) {
        return Math.abs(key.hashCode() % clusterClients.size());
    }

    @Override
    public void close() throws IOException {
        for (ServerStrategy clusterClient : clusterClients) {
            if (clusterClient == this) {
                super.close();
            } else {
                clusterClient.close();
            }
        }
    }
}
