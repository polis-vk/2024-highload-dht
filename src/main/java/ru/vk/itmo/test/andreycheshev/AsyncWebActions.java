package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class AsyncWebActions {
    private final Executor putResponseExecutor = Executors.newFixedThreadPool(6, new WorkerThreadFactory("Put-thread"));
    private final Executor remoteCallExecutor = Executors.newFixedThreadPool(6, new WorkerThreadFactory("RemoteCall-thread"));
    private final Executor senderExecutor = Executors.newFixedThreadPool(6, new WorkerThreadFactory("Sender-thread"));
    private final Executor localExecutor = Executors.newFixedThreadPool(6, new WorkerThreadFactory("LocalCall-thread"));

    private final HttpClient httpClient = HttpClient.newBuilder()
            .executor(remoteCallExecutor)
            .connectTimeout(Duration.ofMillis(500))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final HttpProvider httpProvider;

    public AsyncWebActions(HttpProvider httpProvider) {
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
                ).handleAsync(
                        (future, _) -> {
                            HttpUtils.sendBadRequest(session);
                            return future;
                        },
                        senderExecutor
                );
    }

//} catch (SocketTimeoutException e) {
//        LOGGER.error("Request processing time exceeded on another node", e);
//            } catch (PoolException | HttpException | IOException e) {
//        LOGGER.error("An error occurred when processing a request on another node", e);
//            }

    public CompletableFuture<Void> processLocallyToCollect(
            int method,
            String id,
            Request request,
            long timestamp,
            ResponseCollector collector) {

        return getLocalFuture(method, id, request, timestamp)
                .thenApplyAsync(
                        collector::add,
                        putResponseExecutor
                )
                .exceptionallyAsync(
                        (_) -> collector.incrementResponsesCounter(),
                        putResponseExecutor
                )
                .thenAcceptAsync(
                        condition -> {
                            if (condition) {
                                collector.sendResponse();
                            }
                        },
                        senderExecutor
                );
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

        return httpClient
                .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApplyAsync(
                        response -> { // java.net response
                            return collector.add(
                                    HttpUtils.getElementsFromJavaNetResponse(response)
                            );
                        },
                        putResponseExecutor
                )
                .exceptionallyAsync(
                        (_) -> collector.incrementResponsesCounter(),
                        putResponseExecutor
                )
                .thenAcceptAsync(
                        condition -> {
                            if (condition) {
                                collector.sendResponse();
                            }
                        },
                        senderExecutor
                );
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
                localExecutor
        );
    }
}
