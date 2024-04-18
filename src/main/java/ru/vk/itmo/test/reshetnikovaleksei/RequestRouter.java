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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ru.vk.itmo.test.reshetnikovaleksei.HttpServerImpl.HTTP_TIMESTAMP_HEADER_NAME;
import static ru.vk.itmo.test.reshetnikovaleksei.HttpServerImpl.REDIRECTED_REQUEST_HEADER_NAME;
import static ru.vk.itmo.test.reshetnikovaleksei.HttpServerImpl.TIMESTAMP_HEADER_NAME;

public class RequestRouter implements Closeable {
    private static final String REQUEST_PARAMS = "/v0/entity?id=";

    private final List<String> nodes;
    private final HttpClient httpClient;

    public RequestRouter(ServiceConfig config) {
        this.nodes = config.clusterUrls();
        this.httpClient = HttpClient.newHttpClient();
    }

    public Response redirect(String node, Request request, String entryId)
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        HttpRequest redirectRequest = HttpRequest.newBuilder(URI.create(node + REQUEST_PARAMS + entryId))
                .method(request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .header(REDIRECTED_REQUEST_HEADER_NAME, "true")
                .build();

        HttpResponse<byte[]> httpResponse = httpClient
                .sendAsync(redirectRequest, HttpResponse.BodyHandlers.ofByteArray())
                .get(500, TimeUnit.MILLISECONDS);

        Response response = new Response(Integer.toString(httpResponse.statusCode()), httpResponse.body());
        long timestamp = httpResponse.headers().firstValueAsLong(HTTP_TIMESTAMP_HEADER_NAME).orElse(0);
        response.addHeader(TIMESTAMP_HEADER_NAME + ": " + timestamp);

        return response;
    }

    public List<String> getNodesByEntityId(String id, int from) {
        int partition = getNodeIdByEntityId(id);

        List<String> result = new ArrayList<>();
        for (int i = 0; i < from; i++) {
            int idx = (partition + i) % nodes.size();
            result.add(nodes.get(idx));
        }

        return result;
    }

    private int getNodeIdByEntityId(String id) {
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
        return nodeId;
    }

    @Override
    public void close() {
        httpClient.close();
    }
}
