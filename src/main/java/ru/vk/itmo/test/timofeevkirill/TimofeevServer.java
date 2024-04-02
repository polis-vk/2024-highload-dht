package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.timofeevkirill.dao.Dao;
import ru.vk.itmo.test.timofeevkirill.dao.TimestampEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import static ru.vk.itmo.test.timofeevkirill.Settings.MAX_PROCESSING_TIME_FOR_REQUEST;
import static ru.vk.itmo.test.timofeevkirill.Settings.VERSION_PREFIX;

public class TimofeevServer extends HttpServer {
    public static final String PATH = VERSION_PREFIX + "/entity";
    private static final Logger logger = LoggerFactory.getLogger(TimofeevServer.class);
    private static final String TOO_MANY_REQUESTS_RESPONSE = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS_RESPONSE = "504 Not Enough Replicas";
    private final ThreadPoolExecutor threadPoolExecutor;
    private final TimofeevProxyService proxyService;
    private final ServiceConfig serviceConfig;
    private final RequestHandler requestHandler;

    public TimofeevServer(
            ServiceConfig serviceConfig,
            Dao<MemorySegment, TimestampEntry<MemorySegment>> dao,
            ThreadPoolExecutor threadPoolExecutor,
            TimofeevProxyService proxyService
    ) throws IOException {
        super(createServerConfig(serviceConfig));
        this.serviceConfig = serviceConfig;
        this.threadPoolExecutor = threadPoolExecutor;
        this.proxyService = proxyService;
        this.requestHandler = new RequestHandler(dao);
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;

        return serverConfig;
    }

    @Path(VERSION_PREFIX + "/status")
    public Response status() {
        return Response.ok("OK");
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = Settings.SUPPORTED_METHODS.contains(request.getMethod())
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            long start = System.nanoTime();
            threadPoolExecutor.execute(() -> {
                try {
                    processRequest(request, session, start);
                } catch (IOException e) {
                    logger.error("Exception while sending close connection: ", e);
                    session.scheduleClose();
                }
            });
        } catch (RejectedExecutionException e) {
            session.sendResponse(new Response(TOO_MANY_REQUESTS_RESPONSE, Response.EMPTY));
        }
    }

    private void processRequest(Request request, HttpSession session, long startTime) throws IOException {
        boolean isTimeout = System.nanoTime() - startTime > MAX_PROCESSING_TIME_FOR_REQUEST;
        if (isTimeout) {
            session.sendResponse(new Response(Response.REQUEST_TIMEOUT, Response.EMPTY));
            return;
        }

        if (!Settings.SUPPORTED_METHODS.contains(request.getMethod())) {
            handleDefault(request, session);
            return;
        }

        try {
            RequestData parameters = new RequestData(request, serviceConfig.clusterUrls().size());
            if (request.getHeader(RequestData.SELF_PROCESS_HEADER) == null) {
                processFirstRequest(request, session, parameters);
            } else {
                session.sendResponse(requestHandler.handle(request, parameters.id));
            }
        } catch (IllegalArgumentException e) {
            session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
        } catch (Exception e) {
            if (e.getClass() == HttpException.class) {
                logger.error("Http exception: ", e);
                session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
            } else {
                // for like unexpected NPE
                logger.error("Unexpected exception: ", e);
                session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
            }
        }
    }

    private void processFirstRequest(Request request, HttpSession session, RequestData parameters) throws IOException {
        List<String> nodeUrls = proxyService.getNodesByHash(parameters.from);
        if (nodeUrls.size() < parameters.from) {
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        }

        boolean isSelfProcessing = nodeUrls.remove(serviceConfig.selfUrl());
        Map<String, Response> responses = new HashMap<>();

        try {
            responses = proxyService.proxyAsyncRequests(request, nodeUrls, parameters.id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        } catch (ExecutionException e) {
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        }

        if (isSelfProcessing) {
            responses.put(serviceConfig.selfUrl(), requestHandler.handle(request, parameters.id));
        }

        List<Response> validResponses = responses.values().stream()
                .filter(response -> isSuccessProcessed(response.getStatus()))
                .collect(Collectors.toList());
        if (validResponses.size() >= parameters.ack) {
            if (request.getMethod() == Request.METHOD_GET) {
                validResponses.sort(
                        Comparator.comparingLong(r -> {
                                    String timestamp = r.getHeader(RequestData.NIO_TIMESTAMP_STRING_HEADER);
                                    return timestamp == null ? 0 : Long.parseLong(timestamp);
                                }
                        )
                );
                session.sendResponse(validResponses.getLast());
            } else {
                session.sendResponse(validResponses.getFirst());
            }
        } else {
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        }

    }

    private boolean isSuccessProcessed(int status) {
        // not server and time limit errors
        return status < 500;
    }
}
