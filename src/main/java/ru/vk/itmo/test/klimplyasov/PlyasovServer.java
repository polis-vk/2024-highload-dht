package ru.vk.itmo.test.klimplyasov;

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
import ru.vk.itmo.test.klimplyasov.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PlyasovServer extends HttpServer {

    private final ReferenceDao dao;
    private final ExecutorService executorService;
    private static final Logger logger = LoggerFactory.getLogger(Server.class.getName());

    public PlyasovServer(ServiceConfig config, ReferenceDao dao, ExecutorService executorService) throws IOException {
        super(createConfig(config));
        this.dao = dao;
        this.executorService = executorService;
    }

    public static HttpServerConfig createConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = serviceConfig.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[] {acceptorConfig};
        serverConfig.closeSessions = true;

        return serverConfig;
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        Entry<MemorySegment> value = dao.get(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)));
        return value == null
                ? new Response(Response.NOT_FOUND, Response.EMPTY)
                : Response.ok(value.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response put(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty()) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        dao.upsert(new BaseEntry<>(
                MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(request.getBody())
                )
        );
        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id) {
        if (id.isEmpty()) return new Response(Response.BAD_REQUEST, Response.EMPTY);

        dao.upsert(new BaseEntry<>(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)), null));
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Set<Integer> allowedMethods = Set.of(
                Request.METHOD_GET,
                Request.METHOD_PUT,
                Request.METHOD_DELETE
        );

        Response response = allowedMethods.contains(request.getMethod())
                ? new Response(Response.BAD_REQUEST, Response.EMPTY)
                : new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);

        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        executorService.execute(() -> {
            handleRequestAsync(request, session);
        });
    }

    public void handleRequestAsync(Request request, HttpSession session) {
        try {
            super.handleRequest(request, session);
        } catch (RejectedExecutionException executionException) {
            handleRejectedExecutionException(executionException, session);
        } catch (Exception e) {
            handleOtherExceptions(e, session);
        }
    }

    private void handleRejectedExecutionException(RejectedExecutionException executionException, HttpSession session) {
        try {
            logger.error("Error handling rejected execution", executionException);
            session.sendError(Response.SERVICE_UNAVAILABLE, "");
        } catch (IOException ioException) {
            handleIoException(ioException, session);
        }
    }

    private void handleOtherExceptions(Exception e, HttpSession session) {
        try {
            String response = e instanceof HttpException ? Response.BAD_REQUEST : Response.INTERNAL_ERROR;
            session.sendError(response, "");
        } catch (IOException ex) {
            handleIoException(ex, session);
        }
    }

    private void handleIoException(IOException ioException, HttpSession session) {
        logger.error("IO Exception occurred", ioException);
        session.close();
        Thread.currentThread().interrupt();
    }

    @Path("/v0/status")
    public Response status() {
        return Response.ok("OK");
    }
}
