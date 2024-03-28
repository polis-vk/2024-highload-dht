package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.ServiceInfo;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.Headers;
import ru.vk.itmo.test.kovalevigor.server.util.Parameters;
import ru.vk.itmo.test.kovalevigor.server.util.Responses;

import java.io.IOException;
import java.util.Collection;

import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.GOOD_STATUSES;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.compareTimestamps;

public class ServerReplicationStrategyDecorator extends ServerStrategyDecorator {

    private final ServiceInfo serviceInfo;

    public ServerReplicationStrategyDecorator(ServerStrategy httpServer, ServiceInfo serviceInfo) {
        super(httpServer);
        this.serviceInfo = serviceInfo;
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        if (Headers.hasHeader(request, Headers.REPLICATION)) {
            return super.handleRequest(request, session);
        }
        Response replicasResponse = null;
        Headers.addHeader(request, Headers.REPLICATION, "");
        int ack = Parameters.getParameter(request, Parameters.ACK, Integer::parseInt, serviceInfo.getQuorum());
        int from = Parameters.getParameter(request, Parameters.FROM, Integer::parseInt, serviceInfo.getClusterSize());
        if (ack > from || ack == 0) {
            return Responses.BAD_REQUEST.toResponse();
        }

        Collection<ServerStrategy> strategies = serviceInfo.getPartitionStrategy(
                this,
                Parameters.getParameter(request, Parameters.ID),
                from
        );

        int responseCount = 0;
        for (ServerStrategy strategy : strategies) {
            Response replicaResponse = strategy == this
                    ? super.handleRequest(request, session)
                    : strategy.handleRequest(request, session);
            if (replicaResponse != null && GOOD_STATUSES.contains(replicaResponse.getStatus())) {
                responseCount++;
                replicasResponse = mergeResponses(replicasResponse, replicaResponse);
            }
        }
        Response response;
        if (responseCount >= ack) {
            if (replicasResponse == null) {
                response = Responses.NOT_FOUND.toResponse();
            } else {
                response = new Response(replicasResponse);
            }
        } else {
            response = Responses.NOT_ENOUGH_REPLICAS.toResponse();
        }
        return response;
    }

    private Response mergeResponses(Response lhs, Response rhs) {
        if (lhs == null) {
            return rhs;
        } else if (rhs == null) {
            return lhs;
        }

        int compare = compareTimestamps(getTimestamp(lhs), getTimestamp(rhs));
        if (compare == 0) {
            return rhs;
        }
        return compare > 0 ? lhs : rhs;
    }

    private static String getTimestamp(Response response) {
        return Headers.getHeader(response, Headers.TIMESTAMP);
    }
}
