package ru.vk.itmo.test.smirnovandrew;

import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

public final class MyServerUtil {
    public static final String ROOT = "/v0/entity";
    public static final String X_SENDER_NODE = "X-SenderNode";
    public static final String X_TIMESTAMP = "X-TimeStamp";
    public static final Map<Integer, String> HTTP_CODES = Map.of(
            HttpURLConnection.HTTP_OK, Response.OK,
            HttpURLConnection.HTTP_ACCEPTED, Response.ACCEPTED,
            HttpURLConnection.HTTP_CREATED, Response.CREATED,
            HttpURLConnection.HTTP_NOT_FOUND, Response.NOT_FOUND,
            HttpURLConnection.HTTP_BAD_REQUEST, Response.BAD_REQUEST,
            HttpURLConnection.HTTP_INTERNAL_ERROR, Response.INTERNAL_ERROR
    );
    public static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    public static final long DURATION = 1000L;

    private MyServerUtil() {
    }

    public static HttpServerConfig generateServerConfig(ServiceConfig config) {
        var serverConfig = new HttpServerConfig();
        var acceptorsConfig = new AcceptorConfig();

        acceptorsConfig.port = config.selfPort();
        acceptorsConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorsConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    public static void sendEmpty(HttpSession session, Logger logger, String message) {
        try {
            session.sendResponse(new Response(message, Response.EMPTY));
        } catch (IOException e) {
            logger.info(e.getMessage());
        }
    }

    public static long headerTimestampToLong(Response r) {
        String header = r.getHeader("X-TimeStamp: ");
        if (header == null) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(header);
    }

    public static Response processingResponse(HttpResponse<byte[]> response) {
        String statusCode = HTTP_CODES.getOrDefault(response.statusCode(), null);
        if (statusCode == null) {
            return new Response(Response.INTERNAL_ERROR, response.body());
        } else {
            Response newResponse = new Response(statusCode, response.body());
            long timestamp = response.headers().firstValueAsLong(X_TIMESTAMP).orElse(0);
            newResponse.addHeader(X_TIMESTAMP + ": " + timestamp);
            return newResponse;
        }
    }

    public static Response getResults(
            int from,
            int ack,
            List<CompletableFuture<Response>> completableResults,
            Logger logger
    ) throws ExecutionException, InterruptedException {
        var okResponses = new ArrayList<Response>();
        var okResponsesCount = new AtomicInteger();
        var failedResponsesCount = new AtomicInteger();
        var answer = new CompletableFuture<Response>();

        BiConsumer<Response, Throwable> whenComplete = (r, throwable) -> {
            if (throwable == null || r.getStatus() < 500) {
                okResponsesCount.incrementAndGet();
                okResponses.add(r);
            } else {
                failedResponsesCount.incrementAndGet();
            }

            if (okResponsesCount.get() == ack) {
                answer.complete(okResponses.stream()
                        .max(Comparator.comparingLong(MyServerUtil::headerTimestampToLong))
                        .get());
            }

            if (failedResponsesCount.get() == from - ack + 1) {
                answer.complete(new Response(MyServerUtil.NOT_ENOUGH_REPLICAS, Response.EMPTY));
            }
        };

        completableResults.forEach(completableFuture -> {
            var responseFuture = completableFuture.whenComplete(whenComplete);
            if (responseFuture == null) {
                logger.info("Error completable future is null!");
            }
        });

        try {
            return answer.get(MyServerUtil.DURATION, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.info("Too long waiting for response: " + e.getMessage());
            return new Response(MyServerUtil.NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }
    }
}
