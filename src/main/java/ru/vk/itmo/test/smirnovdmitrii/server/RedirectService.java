package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Executor;

public class RedirectService {

    @DhtValue("cluster.redirect.timeout:100")
    private static int REDIRECT_TIMEOUT;
    @DhtValue("server.connection.timeout.millis:100")
    private static int CONNECTION_TIMEOUT;
    private final HttpClient client;
    private static final Logger logger = LoggerFactory.getLogger(RedirectService.class);

    public RedirectService(final Executor asyncExecutor) {
        this.client = HttpClient.newBuilder()
                .executor(asyncExecutor)
                .connectTimeout(Duration.ofMillis(CONNECTION_TIMEOUT))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void redirectAsync(
            final String url,
            final Request request,
            final ProcessResultHandler handler
    ) {
        logger.trace("sending redirect to node {}", request.getURI());
        final HttpRequest redirectRequest = createRequest(url, request);
        client.sendAsync(redirectRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(this::convert)
                .whenComplete(handler::add);
    }

    public void redirectSync(
            final String url,
            final Request request,
            final ProcessResultHandler handler
    ) throws IOException, InterruptedException {
        logger.trace("sending redirect to node {}", request.getURI());
        final HttpRequest redirectRequest = createRequest(url, request);
        handler.add(convert(client.send(redirectRequest, HttpResponse.BodyHandlers.ofByteArray())));
    }

    private ProcessResult convert(final HttpResponse<byte[]> response) {
        final HttpHeaders httpHeaders = response.headers();
        final OptionalLong value = httpHeaders.firstValueAsLong(Utils.TIMESTAMP_HEADER_NAME);
        if (value.isEmpty()) {
            return new ProcessResult(response.statusCode(), response.body(), -1);
        }
        return new ProcessResult(response.statusCode(), response.body(), value.getAsLong());
    }

    private HttpRequest createRequest(
            final String url,
            final Request request
    ) {
        final byte[] body = request.getBody();
        return HttpRequest.newBuilder()
                .uri(URI.create(url + request.getURI()))
                .method(
                        request.getMethodName(),
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody()))
                .header(Utils.REDIRECT_HEADER_NAME, "true")
                .timeout(Duration.ofMillis(REDIRECT_TIMEOUT))
                .build();
    }

    public void close() {
        client.close();
    }

}
