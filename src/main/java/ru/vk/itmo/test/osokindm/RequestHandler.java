package ru.vk.itmo.test.osokindm;

import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.osokindm.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class RequestHandler {
    private static final String TIMESTAMP_HEADER = "Request-timestamp";
    private static final String TIMESTAMP_HEADER_LC = "request-timestamp";
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private final HttpClient client;
    private DaoWrapper daoWrapper;

    public RequestHandler(HttpClient client) {
        this.client = client;
    }

    public void setDaoWrapper(DaoWrapper daoWrapper) {
        this.daoWrapper = daoWrapper;
    }

    public Response handleRequestLocally(Request request, String id, long timestamp) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> result = daoWrapper.get(id);
                if (result == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                }

                if (result.value() == null) {
                    Response response = new Response(Response.NOT_FOUND, longToBytes(result.timestamp()));
                    response.addHeader(TIMESTAMP_HEADER + ": " + result.timestamp());
                    return response;
                }
                Response response = Response.ok(result.value().toArray(ValueLayout.JAVA_BYTE));
                response.addHeader(TIMESTAMP_HEADER + ": " + result.timestamp());
                return response;
            }
            case Request.METHOD_PUT -> {
                daoWrapper.upsert(id, request, timestamp);
                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                daoWrapper.delete(id, timestamp);
                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    public CompletableFuture<Response> forwardRequestToNode(Request request, Node node, long timestamp) {
        try {
            return makeProxyRequest(request, node.address, timestamp);
        } catch (TimeoutException e) {
            node.captureError();
            LOGGER.error(node + " not responding", e);
            return CompletableFuture.completedFuture(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(null);

        } catch (ExecutionException | IOException e) {
            LOGGER.error(node + " not responding", e);
            return CompletableFuture.completedFuture(null);

        }
    }

    private CompletableFuture<Response> makeProxyRequest(Request request, String nodeAddress, long timestamp)
            throws ExecutionException, InterruptedException, TimeoutException, IOException {
        byte[] body = request.getBody();
        if (body == null) {
            body = Response.EMPTY;
        }
        HttpRequest proxyRequest = HttpRequest
                .newBuilder(URI.create(nodeAddress + request.getURI()))
                .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                .method(request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        LOGGER.info("checking request " + proxyRequest.headers().toString());

        return client
                .sendAsync(proxyRequest, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(this::processedResponse);
    }

    private Response processedResponse(HttpResponse<byte[]> response) {
        long timestamp = response.headers().map().containsKey(TIMESTAMP_HEADER_LC)
                ? Long.parseLong(response.headers().map().get(TIMESTAMP_HEADER_LC).getFirst())
                : -1;

        if (response.body().length == 0 && timestamp == -1) {
            return new Response(String.valueOf(response.statusCode()), Response.EMPTY);
        }
        Response processedResponse = new Response(String.valueOf(response.statusCode()), response.body());
        processedResponse.addHeader(TIMESTAMP_HEADER + ": " + timestamp);
        return processedResponse;
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte) (l & 0xFF);
            l >>= Byte.SIZE;
        }
        return result;
    }
}
