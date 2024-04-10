package ru.vk.itmo.test.vadimershov.hash;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class Main {

    public static void main(String[] args) {
        HttpClient httpClient = HttpClient.newHttpClient();
        httpClient.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:8000"))
                        .GET()
                        .timeout(Duration.ofMillis(100))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray()).join();
    }
}
