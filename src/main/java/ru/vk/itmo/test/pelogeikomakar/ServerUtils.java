package ru.vk.itmo.test.pelogeikomakar;

import one.nio.http.HttpServerConfig;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Hash;
import org.slf4j.Logger;
import ru.vk.itmo.ServiceConfig;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public final class ServerUtils {

    private ServerUtils() {
        throw new UnsupportedOperationException("cannot be instantiated");
    }

    public static String[] getServerUrlsForKey(String key, int from, List<String> clusterUrls) {
        NavigableMap<Integer, String> allCandidates = new TreeMap<>();

        for (String url : clusterUrls) {
            int currentHash = Hash.murmur3(url + key);
            allCandidates.put(currentHash, url);
        }

        String[] resultUrls = new String[from];

        for (int i = 0; i < from; i++) {
            resultUrls[i] = allCandidates.pollLastEntry().getValue();
        }

        return resultUrls;
    }

    public static HttpServerConfig createHttpServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public static Optional<CompletableFuture<Response>> addTask(Supplier<Response> supplier,
                                                                ExecutorService executor, Logger log) {
        CompletableFuture<Response> futureResponse = null;
        try {
            futureResponse = CompletableFuture.supplyAsync(supplier, executor);
        } catch (RejectedExecutionException e) {
            log.info("not enough queue size while adding task", e);
        }

        return Optional.ofNullable(futureResponse);
    }

    public static HttpRequest buildHttpRequest(String executorNode, Request request, long givenTime,
                          String internalRedirect, String timeHeader) {
        HttpRequest.Builder httpRequestBuilder = HttpRequest.newBuilder(URI.create(executorNode + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header(internalRedirect, "true")
                .timeout(Duration.ofSeconds(1));

        if (givenTime >= 0) {
            httpRequestBuilder = httpRequestBuilder.header(timeHeader, Long.toString(givenTime));
        }

        return httpRequestBuilder.build();
    }
}
