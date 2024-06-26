package ru.vk.itmo;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ServiceInfoExtended extends ServiceInfo {
    private final HttpClient client;

    public ServiceInfoExtended(Service service, ServiceConfig config, HttpClient client) {
        super(service, config, client);
        this.client = client;
    }

    public HttpResponse<byte[]> clusterRange(String start, String end) throws Exception {
        return client.send(
                requestForClusterRange(start, end).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    private HttpRequest.Builder requestForClusterRange(String start, String end) {
        return request(STR."/v0/entities?start=\{start}\{end == null ? "" : (STR."&end=\{end}")}&cluster=1");
    }
}
