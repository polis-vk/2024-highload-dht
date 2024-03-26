package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.timofeevkirill.dao.Dao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static ru.vk.itmo.test.timofeevkirill.Settings.MAX_PROCESSING_TIME_FOR_REQUEST;
import static ru.vk.itmo.test.timofeevkirill.Settings.VERSION_PREFIX;

public class TimofeevServer extends HttpServer {
    public static final String PATH = VERSION_PREFIX + "/entity";
    private static final Logger logger = LoggerFactory.getLogger(TimofeevServer.class);
    private static final String TOO_MANY_REQUESTS_RESPONSE = "429 Too Many Requests";
    private static final String NOT_ENOUGH_REPLICAS_RESPONSE = "504 Not Enough Replicas";
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp:";
    private final ThreadPoolExecutor threadPoolExecutor;
    private final TimofeevProxyService proxyService;
    private final ServiceConfig serviceConfig;
    private final RequestHandler requestHandler;

    public TimofeevServer(
            ServiceConfig serviceConfig,
            Dao dao,
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
            RequestParameters parameters = new RequestParameters(request, serviceConfig.clusterUrls().size());
            if (request.getHeader(TimofeevProxyService.IS_SELF_PROCESS) == null) {
                processFirstRequest(request, session, parameters);
            } else {
                handleRequest(request, session);
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

    private void processFirstRequest(Request request, HttpSession session, RequestParameters parameters) throws IOException {
        List<String> nodeUrls = proxyService.getNodesByHash(parameters.id, parameters.from);
        if (nodeUrls.size() < parameters.from) {
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        }

        boolean isSelfProcessing = nodeUrls.remove(serviceConfig.selfUrl());
        Map<String, Response> responses = proxyService.proxyRequests(request, nodeUrls, parameters.id);

        if (isSelfProcessing) {
            responses.put(serviceConfig.selfUrl(), requestHandler.handle(request, parameters.id));
        }

        List<Response> positiveResponses = getPositiveResponses(responses);
        if (positiveResponses.size() >= parameters.ack) {
            if (request.getMethod() == Request.METHOD_GET) {
                positiveResponses.sort(Comparator.comparingLong(r ->
                        Long.parseLong(r.getHeader(NIO_TIMESTAMP_HEADER))));
                session.sendResponse(positiveResponses.getLast());
            } else {
                session.sendResponse(positiveResponses.getFirst());
            }
        } else {
            session.sendResponse(new Response(NOT_ENOUGH_REPLICAS_RESPONSE, Response.EMPTY));
        }

    }

    private List<Response> getPositiveResponses(Map<String, Response> responses) {
        List<Response> positiveResponses = new ArrayList<>();
        for (Response response : responses.values()) {
            if (response.getStatus() < 500) {
                positiveResponses.add(response);
            }
        }
        return positiveResponses;
    }
}
