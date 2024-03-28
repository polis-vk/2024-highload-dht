package ru.vk.itmo.test.reshetnikovaleksei;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.Hash;
import ru.vk.itmo.ServiceConfig;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class RequestRouter implements Closeable {
    private final List<String> nodes;
    private final HttpClient httpClient;

    public RequestRouter(ServiceConfig config) {
        this.nodes = config.clusterUrls();
        this.httpClient = HttpClient.newHttpClient();
    }

    public Response redirect(String node, Request request) throws IOException, InterruptedException {
        HttpRequest redirectRequest = HttpRequest.newBuilder(URI.create(node + request.getURI()))
                .method(request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .timeout(Duration.ofMillis(500))
                .build();

        HttpResponse<byte[]> httpResponse = httpClient.send(redirectRequest, HttpResponse.BodyHandlers.ofByteArray());
        return new Response(Integer.toString(httpResponse.statusCode()), httpResponse.body());
    }

    public String getNodeByEntityId(String id) {
        int nodeId = 0;
        int maxHash = Hash.murmur3(nodes.getFirst() + id);
        for (int i = 1; i < nodes.size(); i++) {
            String url = nodes.get(i);
            int result = Hash.murmur3(url + id);
            if (maxHash < result) {
                maxHash = result;
                nodeId = i;
            }
        }
        return nodes.get(nodeId);
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
