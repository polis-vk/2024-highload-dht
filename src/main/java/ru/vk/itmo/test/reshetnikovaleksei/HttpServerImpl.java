package ru.vk.itmo.test.reshetnikovaleksei;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import one.nio.util.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.reshetnikovaleksei.dao.BaseEntry;
import ru.vk.itmo.test.reshetnikovaleksei.dao.Dao;
import ru.vk.itmo.test.reshetnikovaleksei.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

public class HttpServerImpl extends HttpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerImpl.class);
    private static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";

    public static final String TIMESTAMP_HEADER_NAME = "X-Request-Timestamp";
    public static final String HTTP_TIMESTAMP_HEADER_NAME = "x-request-timestamp";
    public static final String REDIRECTED_REQUEST_HEADER_NAME = "X-Redirected-Request";

    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private final ExecutorService localExecutorService;
    private final RequestRouter requestRouter;
    private final String selfUrl;
    private final int clusterSize;

    public HttpServerImpl(ServiceConfig config,
                          Dao<MemorySegment, Entry<MemorySegment>> dao,
                          ExecutorService executorService,
                          ExecutorService localExecutorService,
                          RequestRouter requestRouter) throws IOException {
        super(createConfig(config));
        this.dao = dao;
        this.executorService = executorService;
        this.localExecutorService = localExecutorService;
        this.requestRouter = requestRouter;
        this.selfUrl = config.selfUrl();
        this.clusterSize = config.clusterUrls().size();
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        if (request.getMethod() == Request.METHOD_GET) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        } else {
            session.sendResponse(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    super.handleRequest(request, session);
                } catch (IOException e) {
                    processIOException(request, session, e);
                } catch (Exception e) {
                    LOGGER.error("Failed to handle request: {} with error: {}", request, e.getMessage());
                    try {
                        if (e.getClass() == HttpException.class) {
                            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
                        } else {
                            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
                        }
                    } catch (IOException ex) {
                        processIOException(request, session, ex);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.error("Failed to execute task for request: {} with error: {}", request, e.getMessage());
            try {
                session.sendResponse(new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY));
            } catch (IOException ex) {
                processIOException(request, session, ex);
            }
        }
    }

    @Path("/v0/entity")
    public Response entity(Request request,
                           @Param(value = "id") String id,
                           @Param(value = "from") Integer from,
                           @Param(value = "ack") Integer ack
    ) throws ExecutionException, InterruptedException {
        if (id == null || id.isBlank()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (from == null) {
            from = clusterSize;
        }

        if (ack == null) {
            ack = (from + 1) / 2;
        }

        if (from < 0 || from > clusterSize || from < ack || ack <= 0) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (request.getHeader(REDIRECTED_REQUEST_HEADER_NAME) == null) {
            List<Response> notErrorResponses = extractFuturesAndGetNotErrorResponses(
                    sendRequestsAndGetResponses(
                            request, id, requestRouter.getNodesByEntityId(id, from)
                    )
            );

            if (notErrorResponses.size() >= ack) {
                return request.getMethod() == Request.METHOD_GET
                        ? getLastResponse(notErrorResponses)
                        : notErrorResponses.getFirst();
            }

            return new Response(NOT_ENOUGH_REPLICAS, Response.EMPTY);
        }

        return invokeLocal(request, id).get();
    }

    private CompletableFuture<Response> invokeLocal(Request request, String id) {
        CompletableFuture<Response> completableFuture = new CompletableFuture<>();

        localExecutorService.execute(() -> {
            switch (request.getMethod()) {
                case Request.METHOD_GET -> {
                    MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
                    Entry<MemorySegment> entry = dao.get(key);
                    if (entry == null || entry.value() == null) {
                        Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                        response.addHeader(TIMESTAMP_HEADER_NAME + ": " + (entry != null ? entry.timestamp() : 0));

                        completableFuture.complete(response);
                        return;
                    }

                    Response response = Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE));
                    response.addHeader(TIMESTAMP_HEADER_NAME + ": " + entry.timestamp());
                    completableFuture.complete(response);
                }
                case Request.METHOD_PUT -> {
                    MemorySegment key = MemorySegment.ofArray(Utf8.toBytes(id));
                    MemorySegment value = MemorySegment.ofArray(request.getBody());
                    dao.upsert(new BaseEntry<>(key, value, System.currentTimeMillis()));
                    completableFuture.complete(new Response(Response.CREATED, Response.EMPTY));
                }
                case Request.METHOD_DELETE -> {
                    MemorySegment key = MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
                    dao.upsert(new BaseEntry<>(key, null, System.currentTimeMillis()));
                    completableFuture.complete(new Response(Response.ACCEPTED, Response.EMPTY));
                }
                default -> completableFuture.complete(new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY));
            }
        });

        return completableFuture;
    }

    private List<CompletableFuture<Response>> sendRequestsAndGetResponses(
            Request request, String id, List<String> nodes
    ) {
        List<CompletableFuture<Response>> responses = new ArrayList<>();
        for (String node : nodes) {
            if (node.equals(selfUrl)) {
                responses.add(invokeLocal(request, id));
                continue;
            }

            try {
                responses.add(requestRouter.redirect(node, request, id));
            } catch (TimeoutException e) {
                LOGGER.error("timeout while invoking remote node");
                responses.add(CompletableFuture.completedFuture(
                        new Response(Response.REQUEST_TIMEOUT, Response.EMPTY)));
            } catch (ExecutionException | IOException e) {
                LOGGER.error("I/O exception while calling remote node");
                responses.add(CompletableFuture.completedFuture(
                        new Response(Response.INTERNAL_ERROR, Response.EMPTY)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("Thread interrupted");
                responses.add(CompletableFuture.completedFuture(
                        new Response(Response.SERVICE_UNAVAILABLE, Response.EMPTY)));
            }
        }

        return responses;
    }

    private List<Response> extractFuturesAndGetNotErrorResponses(List<CompletableFuture<Response>> futures) {
        List<Response> responses = futures.stream().map(future -> {
            try {
                return future.get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.error("Got error while redirecting request: {}", e.getMessage());
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
        }).toList();

        List<Response> not5xxResponses = new ArrayList<>();
        for (Response response : responses) {
            if (response.getStatus() < 500 && response.getStatus() != 429) {
                not5xxResponses.add(response);
            }
        }

        return not5xxResponses;
    }

    private Response getLastResponse(List<Response> notErrorResponses) {
        Response lastResponse = null;
        long maxTimestamp = Long.MIN_VALUE;

        for (Response response : notErrorResponses) {
            long timestamp = Long.parseLong(response.getHeader(TIMESTAMP_HEADER_NAME + ": "));
            if (timestamp > maxTimestamp) {
                maxTimestamp = timestamp;
                lastResponse = response;
            }
        }
        return lastResponse;
    }

    private void processIOException(Request request, HttpSession session, IOException e) {
        LOGGER.error("Failed to send response for request: {} with error: {}", request, e.getMessage());
        session.close();
    }

    private static HttpServerConfig createConfig(ServiceConfig config) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        httpServerConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }
}
