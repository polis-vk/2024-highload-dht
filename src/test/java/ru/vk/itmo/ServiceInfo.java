package ru.vk.itmo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

public class ServiceInfo {
    private final Service service;
    private final ServiceConfig config;
    private final HttpClient client;

    public ServiceInfo(Service service, ServiceConfig config, HttpClient client) {
        this.service = service;
        this.config = config;
        this.client = client;
    }

    public String url() {
        return config.selfUrl();
    }

    public void start() throws Exception {
        service.start().get(10, TimeUnit.SECONDS);
    }

    public void stop() throws Exception {
        service.stop().get(10, TimeUnit.SECONDS);
    }

    public void cleanUp() throws Exception {
        stop();
        FileUtils.delete(config.workingDir());
    }

    public HttpResponse<byte[]> get(String key) throws Exception {
        return client.send(
                requestForKey(key).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    public HttpResponse<byte[]> delete(String key) throws Exception {
        return client.send(
                requestForKey(key).DELETE().build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    public HttpResponse<byte[]> upsert(String key, byte[] data) throws Exception {
        return client.send(
                requestForKey(key).PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    public HttpResponse<byte[]> post(String key, byte[] data) throws Exception {
        return client.send(
                requestForKey(key).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
                HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(url() + path));
    }

    private HttpRequest.Builder requestForKey(String key) {
        return request(STR."/v0/entity?id=\{key}");
    }
}
