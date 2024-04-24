package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.ServiceInfo;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.Headers;
import ru.vk.itmo.test.kovalevigor.server.util.Parameters;
import ru.vk.itmo.test.kovalevigor.server.util.Paths;
import ru.vk.itmo.test.kovalevigor.server.util.Responses;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.GOOD_STATUSES;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.REMOTE_TIMEOUT_VALUE;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.mergeResponses;

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

        Response replicasResponse = null;
        Headers.addHeader(request, Headers.REPLICATION, "");
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
        return checkReplicaResponse(responseCount, ack, replicasResponse);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public CompletableFuture<Response> handleRequestAsync(
            Request request,
            HttpSession session,
            Executor executor
    ) {
        if (Paths.getPathOrThrow(request.getPath()).isLocal() || Headers.hasHeader(request, Headers.REPLICATION)) {
            return super.handleRequestAsync(request, session, executor);
        }
        int ack = Parameters.getParameter(request, Parameters.ACK, Integer::parseInt, serviceInfo.getQuorum());
        int from = Parameters.getParameter(request, Parameters.FROM, Integer::parseInt, serviceInfo.getClusterSize());
        if (ack > from || ack == 0) {
            return CompletableFuture.completedFuture(Responses.BAD_REQUEST.toResponse());
        }

        Collection<ServerStrategy> strategies = serviceInfo.getPartitionStrategy(
                this,
                Parameters.getParameter(request, Parameters.ID),
                from
        );

        Headers.addHeader(request, Headers.REPLICATION, "");
        AckEitherCompletableFuture result = new AckEitherCompletableFuture(ack, from);
        for (ServerStrategy strategy : strategies) {
            CompletableFuture<Response> future =
                    strategy == this
                            ? super.handleRequestAsync(request, session, executor)
                            : strategy.handleRequestAsync(request, session, executor);
            future.whenCompleteAsync(result::markCompletedFuture, executor);
        }
        return result.orTimeout(REMOTE_TIMEOUT_VALUE, TimeUnit.SECONDS);
    }

    private static Response checkReplicaResponse(int responseCount, int ack, Response replicasResponse) {
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
}
