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
import ru.vk.itmo.test.grunskiialexey.dao.MemorySegmentDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class DaoServer extends HttpServer {
    private static final int FLUSH_THRESHOLD_BYTES = 1028;
    private final ServiceConfig config;
    private MemorySegmentDao dao;

    public DaoServer(ServiceConfig config) throws IOException {
        super(createServerConfig(config));
        this.config = config;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig config) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;

        serverConfig.acceptors = new AcceptorConfig[]{acceptorConfig};
        serverConfig.selectors = 1;
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static MemorySegmentDao createDao(ServiceConfig config) throws IOException {
        return new MemorySegmentDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
    }

    public static void main(String[] args) throws IOException {
        DaoServer server = new DaoServer(new ServiceConfig(
                8081, "http://localhost",
                List.of("http://localhost"),
                Files.createTempDirectory(".")
        ));
        server.start();
    }

    @Path("/v0/entity")
//    :TODO Check profiling with request method s
//    @RequestMethod(Request.METHOD_GET)
    public void handleQueries(Request request, HttpSession session) throws IOException {
        final String methodName = request.getMethodName();
        String id = request.getParameter("id");
        if (id == null || id.isBlank() || id.trim().equals("=")) {
            session.sendError(Response.BAD_REQUEST, "Incorrect value of 'id' parameter");
            return;
        }
        final MemorySegment key = MemorySegment.ofArray(id.substring(1).getBytes(StandardCharsets.UTF_8));
        switch (methodName) {
            case "GET" -> {
                final Entry<MemorySegment> entry = dao.get(key);
                // :TODO should go to check how to working .get(key) - may it has entry.value() or not
                //  :TODO but actualy it's not necessary
                if (entry == null || entry.value() == null) {
                    session.sendError(Response.NOT_FOUND, "Not found value associate with key" + id);
                    return;
                }

                session.sendResponse(Response.ok(entry.value().toArray(ValueLayout.JAVA_BYTE)));
            }
            case "PUT" -> {
                final Entry<MemorySegment> entry = new BaseEntry<>(key, MemorySegment.ofArray(request.getBody()));
                dao.upsert(entry);

                session.sendResponse(new Response(Response.CREATED));
            }
            case "DELETE" -> {
                final Entry<MemorySegment> entry = new BaseEntry<>(key, null);
                dao.upsert(entry);

                session.sendResponse(new Response(Response.ACCEPTED));
            }
            default -> session.sendError(Response.METHOD_NOT_ALLOWED, "Not allowed method");
        }
        session.close();
    }

    // :TODO we need to handle exceptions
    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendError(Response.BAD_REQUEST, "This path is unavailable");
    }

    public void loadDao() throws IOException {
        dao = createDao(config);
    }

    public void closeDao() throws IOException {
        dao.close();
    }
}
