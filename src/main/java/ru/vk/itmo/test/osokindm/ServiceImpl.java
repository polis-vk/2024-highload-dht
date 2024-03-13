package ru.vk.itmo.test.osokindm;

import one.nio.http.HttpServerConfig;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ServiceImpl implements Service {

    private static final int MEMORY_LIMIT_BYTES = 8 * 1024 * 1024;
    private static final int CONNECTION_TIMEOUT_MS = 250;
    private static final String DEFAULT_PATH = "/v0/entity";
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ServiceConfig config;
    private RendezvousRouter router;
    private DaoWrapper daoWrapper;
    private HttpServerImpl server;
    private final HttpClient client;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
        client = HttpClient.newBuilder()
                .connectTimeout(Duration.of(CONNECTION_TIMEOUT_MS, ChronoUnit.MILLIS))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            daoWrapper = new DaoWrapper(new Config(config.workingDir(), MEMORY_LIMIT_BYTES));
            router = new RendezvousRouter(config.clusterUrls());
            server = new HttpServerImpl(createServerConfig(config.selfPort()));
            server.addRequestHandlers(this);
            server.start();
            LOGGER.debug("Server started");
        } catch (IOException e) {
            LOGGER.error("Error occurred while starting the server");
            throw new IOException(e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        server.stop();
        try {
            daoWrapper.stop();
        } catch (IOException e) {
            LOGGER.error("Error occurred while closing database");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Path(DEFAULT_PATH)
    public Response entity(Request request, @Param(value = "id", required = true) String id) throws TimeoutException {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, "Invalid id".getBytes(StandardCharsets.UTF_8));
        }

        Node node = router.getNode(id);
        if (!node.isAlive()) {
            return new Response(Response.SERVICE_UNAVAILABLE, "Node is unavailable".getBytes(StandardCharsets.UTF_8));
        }

        if (!node.address.equals(config.selfUrl())) {
            return forwardRequestToNode(request, node);
        }

        return handleRequestLocally(request, id);
    }

    private Response forwardRequestToNode(Request request, Node node) {
        try {
            LOGGER.debug("Request has been forwarded to {}", node.address);
            return makeProxyRequest(request, node.address);
        } catch (TimeoutException e) {
            node.captureError();
            LOGGER.error(node +" not responding", e);
            return new Response(Response.REQUEST_TIMEOUT, Response.EMPTY);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (ExecutionException e) {
            LOGGER.error(node +" not responding", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response handleRequestLocally(Request request, String id) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> result = daoWrapper.get(id);
                if (result != null && result.value() != null) {
                    return Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
                }
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            }
            case Request.METHOD_PUT -> {
                daoWrapper.upsert(id, request);
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                daoWrapper.delete(id);
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private Response makeProxyRequest(Request request, String nodeAddress)
            throws ExecutionException, InterruptedException, TimeoutException {
        byte[] body = request.getBody();
        if (body == null) {
            body = Response.EMPTY;
        }
        HttpRequest proxyRequest = HttpRequest
                .newBuilder(URI.create(nodeAddress + request.getURI()))
                .method(request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client
                .sendAsync(proxyRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(ServiceImpl::processedResponse)
                .get(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static Response processedResponse(HttpResponse<byte[]> response) {
        return switch (response.statusCode()) {
            case HttpURLConnection.HTTP_OK -> new Response(Response.OK, response.body());
            case HttpURLConnection.HTTP_CREATED -> new Response(Response.CREATED, Response.EMPTY);
            case HttpURLConnection.HTTP_BAD_METHOD -> new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            case HttpURLConnection.HTTP_NOT_FOUND -> new Response(Response.NOT_FOUND, Response.EMPTY);
            case HttpURLConnection.HTTP_ACCEPTED -> new Response(Response.ACCEPTED, Response.EMPTY);
            default -> new Response(String.valueOf(response.statusCode()), Response.EMPTY);
        };
    }

    private static HttpServerConfig createServerConfig(int port) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }

}
