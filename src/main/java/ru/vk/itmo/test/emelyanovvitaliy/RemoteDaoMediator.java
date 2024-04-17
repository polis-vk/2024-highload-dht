package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.Request;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static ru.vk.itmo.test.emelyanovvitaliy.MergeDaoMediator.FINAL_EXECUTION_HEADER;

public class RemoteDaoMediator extends DaoMediator {
    protected static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(250);
    public static final String TIMESTAMP_HEADER = "X-Timestamp";
    protected final HttpClient client;
    protected final String url;
    protected final Duration timeout;

    RemoteDaoMediator(HttpClient httpClient, String url) {
        this(httpClient, url, DEFAULT_TIMEOUT);
    }

    RemoteDaoMediator(HttpClient httpClient, String url, Duration timeout) {
        this.client = httpClient;
        this.timeout = timeout;
        this.url = url;
    }

    protected boolean isStatusCodeCorrect(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    void stop() {
        if (!client.isTerminated()) {
            client.close();
        }
    }

    @Override
    boolean isStopped() {
        return client.isTerminated();
    }

    @Override
    protected CompletableFuture<Boolean> put(Request request) {
        return simpleForward(request);
    }

    protected CompletableFuture<Boolean> simpleForward(Request request) {
        try {
            return invoke(request).handle((response, ex) -> {
                    if (response == null) {
                        return false;
                    }
                    return isStatusCodeCorrect(response.statusCode());
                }
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedInterruptedException(e);
        } catch (IOException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    CompletableFuture<TimestampedEntry<MemorySegment>> get(Request request) {
        try {
            return invoke(request).handle(
                    (response, ex) -> {
                        if (response == null) {
                            return null;
                        }
                        return getTimestampedEntry(request, response);
                    }
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedInterruptedException(e);
        } catch (IOException e) {
            return null;
        }

    }

    private TimestampedEntry<MemorySegment> getTimestampedEntry(Request request, HttpResponse<byte[]> response) {
        OptionalLong timestampHeader = response.headers().firstValueAsLong(TIMESTAMP_HEADER);
        long timestamp;
        if (timestampHeader.isEmpty()) {
            timestamp = NEVER_TIMESTAMP;
        } else {
            timestamp = timestampHeader.getAsLong();
        }
        if (isStatusCodeCorrect(response.statusCode()) && timestampHeader.isPresent()) {
            return new TimestampedEntry<>(
                    keyFor(request.getParameter(DhtServer.ID_KEY)),
                    MemorySegment.ofArray(response.body()),
                    timestamp
            );
        } else if (response.statusCode() == 404) {
            return new TimestampedEntry<>(
                    keyFor(request.getParameter(DhtServer.ID_KEY)),
                    null,
                    timestamp
            );
        } else {
            return null;
        }
    }

    @Override
    CompletableFuture<Boolean> delete(Request request) {
        return simpleForward(request);
    }

    private CompletableFuture<HttpResponse<byte[]>> invoke(Request request) throws IOException, InterruptedException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url + request.getURI()))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .setHeader(FINAL_EXECUTION_HEADER, "true")
                .timeout(timeout)
                .build();
        return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
    }
}
