package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.ServiceInfo;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.Headers;
import ru.vk.itmo.test.kovalevigor.server.util.Parameters;

import java.io.IOException;
import java.util.Collection;

import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.GOOD_STATUSES;

public class ServerShardingStrategyDecorator extends ServerStrategyDecorator {
    private final ServiceInfo serviceInfo;

    public ServerShardingStrategyDecorator(ServerStrategy httpServer, ServiceInfo serviceInfo) {
        super(httpServer);
        this.serviceInfo = serviceInfo;
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        if (Headers.hasHeader(request, Headers.REPLICATION)) {
            return super.handleRequest(request, session);
        }
        int from = Parameters.getParameter(
                request,
                Parameters.FROM,
                Integer::parseInt,
                serviceInfo.getClusterSize()
        );

        Collection<ServerStrategy> strategies = serviceInfo.getPartitionStrategy(
                this,
                Parameters.getParameter(request, Parameters.ID),
                from
        );
        for (ServerStrategy strategy : strategies) {
            if (strategy == this) {
                return super.handleRequest(request, session);
            }
            Response response = strategy.handleRequest(request, session);
            if (response != null && GOOD_STATUSES.contains(response.getStatus())) {
                return response;
            }
        }
        return null;
    }
}
