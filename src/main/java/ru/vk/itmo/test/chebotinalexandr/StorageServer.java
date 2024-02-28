package ru.vk.itmo.test.chebotinalexandr;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

public class StorageServer extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(StorageServer.class);
    private static final String PATH = "/v0/entity";
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executor;

    public StorageServer(
            ServiceConfig config,
            Dao<MemorySegment, Entry<MemorySegment>> dao,
            ExecutorService executor
    ) throws IOException {
        super(createConfig(config));
        this.dao = dao;
        this.executor = executor;
    }

    private static HttpServerConfig createConfig(ServiceConfig config) {
        HttpServerConfig httpServerConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.reusePort = true;
        acceptorConfig.port = config.selfPort();
      
        httpServerConfig.acceptors = new AcceptorConfig[]{acceptorConfig};

        httpServerConfig.closeSessions = true;
        return httpServerConfig;
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        Response response = new Response(Response.BAD_REQUEST, Response.EMPTY);
        session.sendResponse(response);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        String id = request.getParameter("id=");
        if (id == null || id.isBlank()) {
            sendEmptyBodyResponse(Response.BAD_REQUEST, session);
            return;
        }

        executor.execute(() -> {
            try {
                super.handleRequest(request, session);
            } catch (IOException e) {
                log.error("Exception during handleRequest: ", e);
                sendEmptyBodyResponse(Response.INTERNAL_ERROR, session);
            }
        });
    }

    @Path(PATH)
    public Response entity(Request request, @Param("id") String id) {
        switch (request.getMethod()) {
            case Request.METHOD_GET -> {
                Entry<MemorySegment> entry = dao.get(fromString(id));

                if (entry == null) {
                    return new Response(Response.NOT_FOUND, Response.EMPTY);
                } else {
                    return Response.ok(toBytes(entry.value()));
                }
            }
            case Request.METHOD_PUT -> {
                Entry<MemorySegment> entry = new BaseEntry<>(
                        fromString(id),
                        fromBytes(request.getBody())
                );
                dao.upsert(entry);

                return new Response(Response.CREATED, Response.EMPTY);
            }
            case Request.METHOD_DELETE -> {
                Entry<MemorySegment> entry = new BaseEntry<>(
                        fromString(id),
                        null
                );
                dao.upsert(entry);

                return new Response(Response.ACCEPTED, Response.EMPTY);
            }
            default -> {
                return new Response(Response.METHOD_NOT_ALLOWED, Response.EMPTY);
            }
        }
    }

    private static void sendEmptyBodyResponse(String responseCode, HttpSession session) {
        Response emptyBodyResponse = new Response(responseCode, Response.EMPTY);
        try {
            session.sendResponse(emptyBodyResponse);
        } catch (IOException e) {
            log.error("Exception during send empty response ", e);
        }
    }

    private static MemorySegment fromString(String data) {
        return MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment fromBytes(byte[] bytes) {
        return MemorySegment.ofArray(bytes);
    }

    private static byte[] toBytes(MemorySegment segment) {
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }
}

