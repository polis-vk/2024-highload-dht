package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AsyncActions {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncActions.class);

    private static final int CPU_THREADS_COUNT = Runtime.getRuntime().availableProcessors();

    private final Executor internalExecutor = Executors.newFixedThreadPool(
            CPU_THREADS_COUNT / 2,
            new WorkerThreadFactory("Internal-thread")
    );
    private final Executor senderExecutor = Executors.newFixedThreadPool(
            CPU_THREADS_COUNT / 2,
            new WorkerThreadFactory("Sender-thread")
    );
    private final Executor localCallExecutor = Executors.newFixedThreadPool(
            CPU_THREADS_COUNT / 2,
            new WorkerThreadFactory("LocalCall-thread")
    );
    private final Executor remoteCallExecutor = Executors.newFixedThreadPool(
            CPU_THREADS_COUNT,
            new WorkerThreadFactory("RemoteCall-thread")
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .executor(remoteCallExecutor)
            .connectTimeout(Duration.ofMillis(500))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final HttpProvider httpProvider;

    public AsyncActions(HttpProvider httpProvider) {
        this.httpProvider = httpProvider;
    }

    public CompletableFuture<Void> processLocallyToSend(
            int method,
            String id,
            Request request,
            long timestamp,
            HttpSession session) {

        return getLocalFuture(method, id, request, timestamp)
                .thenAcceptAsync(
                        elements -> HttpUtils.sendResponse(
                                HttpUtils.getOneNioResponse(method, elements),
                                session
                        ),
                        senderExecutor
                )
                .exceptionallyAsync(
                        exception -> {
                            if (exception.getCause() instanceof UncheckedIOException) {
                                LOGGER.error("Error when sending a request", exception);
                            }
                            return null;
                        },
                        internalExecutor
                );
    }

    public CompletableFuture<Void> processLocallyToCollect(
            int method,
            String id,
            Request request,
            long timestamp,
            ResponseCollector collector) {

        CompletableFuture<Void> future = getLocalFuture(method, id, request, timestamp)
                .thenApplyAsync(
                        collector::add,
                        internalExecutor
                )
                .exceptionallyAsync(
                        exception -> {
                            LOGGER.error("Internal error of the DAO operation", exception);
                            return collector.incrementResponsesCounter();
                        },
                        internalExecutor
                )
                .thenAcceptAsync(
                        condition -> {
                            if (condition) {
                                collector.sendResponse();
                            }
                        },
                        senderExecutor
                );

        return withSendingErrorProcessing(future);
    }

    public CompletableFuture<Void> processRemotelyToCollect(
            String node,
            Request request,
            long timestamp,
            ResponseCollector collector) {

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(node + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .header(HttpUtils.TIMESTAMP_JAVA_NET_HEADER, String.valueOf(timestamp))
                .timeout(Duration.ofMillis(500))
                .build();

        CompletableFuture<Void> future = httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(
                        response -> { // java.net response.
                            return collector.add(
                                    HttpUtils.getElementsFromJavaNetResponse(response)
                            );
                        },
                        internalExecutor
                )
                .exceptionallyAsync(
                        exception -> {
                            LOGGER.error("Error when sending a request to the remote node", exception);
                            return collector.incrementResponsesCounter();
                        },
                        internalExecutor
                )
                .thenAcceptAsync(
                        condition -> {
                            if (condition) {
                                collector.sendResponse();
                            }
                        },
                        senderExecutor
                );

        return withSendingErrorProcessing(future);
    }

    private CompletableFuture<ResponseElements> getLocalFuture(
            int method,
            String id,
            Request request,
            long timestamp) {

        return CompletableFuture.supplyAsync(
                () -> switch (method) {
                    case Request.METHOD_GET -> httpProvider.get(id);
                    case Request.METHOD_PUT -> httpProvider.put(id, request.getBody(), timestamp);
                    default -> httpProvider.delete(id, timestamp);
                },
                localCallExecutor
        );
    }

    private CompletableFuture<Void> withSendingErrorProcessing(
            CompletableFuture<Void> future) {

        return future
                .exceptionallyAsync(
                        exception -> {
                            LOGGER.error("Error when sending a request", exception);
                            return null;
                        },
                        internalExecutor
                );
    }

    public void sendAsync(Response response, HttpSession session) throws AssertionError {
        CompletableFuture<Void> future = withSendingErrorProcessing(
                CompletableFuture.runAsync(
                        () -> HttpUtils.sendResponse(response, session),
                        senderExecutor
                )
        );

        assert future != null;
    }
}
