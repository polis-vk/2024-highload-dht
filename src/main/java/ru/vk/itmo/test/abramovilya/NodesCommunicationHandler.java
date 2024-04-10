package ru.vk.itmo.test.abramovilya;

import one.nio.http.HttpClient;
import one.nio.http.Response;
import ru.vk.itmo.ServiceConfig;

import java.util.Map;
import java.util.Optional;

public final class NodesCommunicationHandler {
    private NodesCommunicationHandler() {
    }

    static Optional<Response> getResponseFromAnotherNode(int nodeNumber, Server.ResponseProducer responseProducer,
                                                         ServiceConfig config, Map<String, HttpClient> httpClients) {
        String nodeUrl = config.clusterUrls().get(nodeNumber);
        if (nodeUrl.equals(config.selfUrl())) {
            return Optional.empty();
        }
        HttpClient client = httpClients.get(nodeUrl);
        try {
            return Optional.of(responseProducer.getResponse(client));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (Exception e) {
            return Optional.of(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        }
    }
}
