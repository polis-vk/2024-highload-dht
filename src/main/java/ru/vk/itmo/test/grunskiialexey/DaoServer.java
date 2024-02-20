package ru.vk.itmo.test.grunskiialexey;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.AcceptorConfig;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.grunskiialexey.dao.MemorySegmentConverter;
import ru.vk.itmo.test.grunskiialexey.dao.MemorySegmentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.List;

public class DaoServer extends HttpServer {
    private static final int FLUSH_THRESHOLD_BYTES = 1028;
    private final MemorySegmentDao dao;

    public DaoServer(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        this.dao = createDao(config);
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static MemorySegmentDao createDao(ServiceConfig config) throws IOException {
        return new MemorySegmentDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
    }

    public static void main(String[] args) throws IOException {
        DaoServer server = new DaoServer(new ServiceConfig(
                8080, "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")
        ));
        server.start();
    }

    @Path("/v0/entity")
    public void handleQueries(Request request, HttpSession session) throws IOException {
        String methodName = request.getMethodName();
        switch (methodName) {
            case "GET" -> {
                final MemorySegment key = MemorySegmentConverter.fromString(request.getParameter("id"));
                final Entry<MemorySegment> entry = dao.get(key);
                if (entry == null || entry.value() == null) {
                    handleDefault(request, session);
                    return;
                }

                session.sendResponse(Response.ok(
                        MemorySegmentConverter.toString(entry.value())
                ));
            }
            case "POST" -> {
                final Entry<MemorySegment> entry = new BaseEntry<>(
                        MemorySegmentConverter.fromString(request.getParameter("id")),
                        MemorySegment.ofArray(request.getBody())
                );
                dao.upsert(entry);

                session.sendResponse(new Response(Response.CREATED));
            }
            case "DELETE" -> {
                final Entry<MemorySegment> entry = new BaseEntry<>(
                        MemorySegmentConverter.fromString(request.getParameter("id")),
                        null
                );
                dao.upsert(entry);

                session.sendResponse(new Response(Response.ACCEPTED));
            }
        }
    }
}
