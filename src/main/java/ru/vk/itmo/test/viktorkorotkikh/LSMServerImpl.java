package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.LSMDaoOutOfMemoryException;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.TooManyFlushesException;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMConstantResponse;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMCustomSession;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMServerResponseWithMemorySegment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class LSMServerImpl extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(LSMServerImpl.class);
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public LSMServerImpl(ServiceConfig serviceConfig, Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        super(createServerConfig(serviceConfig));
        this.dao = dao;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{createAcceptorConfig(serviceConfig.selfPort())};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static AcceptorConfig createAcceptorConfig(int port) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        return acceptorConfig;
    }

    @Path("/v0/entity")
    public void handleEntityRequest(Request request, HttpSession session) throws IOException {
        // validate id parameter
        String id = request.getParameter("id=");
        if (id == null || id.isEmpty()) {
            session.sendResponse(LSMConstantResponse.badRequest(request));
            return;
        }

        Response response;

        try {
            response = switch (request.getMethod()) {
                case METHOD_GET -> handleGetEntity(request, id);
                case METHOD_PUT -> handlePutEntity(request, id);
                case METHOD_DELETE -> handleDeleteEntity(request, id);
                default -> LSMConstantResponse.methodNotAllowed(request);
            };
        } catch (Exception e) {
            log.error("Unexpected error occurred: ", e);
            response = LSMConstantResponse.serviceUnavailable(request);
        }

        session.sendResponse(response);
    }

    private Response handleGetEntity(final Request request, final String id) {
        final Entry<MemorySegment> entry;
        try {
            entry = dao.get(fromString(id));
        } catch (UncheckedIOException e) {
            // sstable get method throws UncheckedIOException
            return LSMConstantResponse.serviceUnavailable(request);
        }
        if (entry == null || entry.value() == null) {
            return LSMConstantResponse.notFound(request);
        }

        return new LSMServerResponseWithMemorySegment(Response.OK, entry.value());
    }

    private Response handlePutEntity(final Request request, final String id) {
        if (request.getBody() == null) {
            return LSMConstantResponse.badRequest(request);
        }

        Entry<MemorySegment> newEntry = new BaseEntry<>(
                fromString(id),
                MemorySegment.ofArray(request.getBody())
        );
        try {
            dao.upsert(newEntry);
        } catch (LSMDaoOutOfMemoryException e) {
            // when entry is too big to be putted into memtable
            return LSMConstantResponse.entityTooLarge(request);
        } catch (TooManyFlushesException e) {
            // when one memory table is in the process of being flushed, and the second is already full
            return LSMConstantResponse.tooManyRequests(request);
        }

        return LSMConstantResponse.created(request);
    }

    private Response handleDeleteEntity(final Request request, final String id) {
        final Entry<MemorySegment> newEntry = new BaseEntry<>(
                fromString(id),
                null
        );
        try {
            dao.upsert(newEntry);
        } catch (LSMDaoOutOfMemoryException e) {
            return LSMConstantResponse.entityTooLarge(request);
        } catch (TooManyFlushesException e) {
            return LSMConstantResponse.tooManyRequests(request);
        }

        return LSMConstantResponse.accepted(request);
    }

    @Path("/v0/compact")
    @RequestMethod(value = {METHOD_GET})
    public Response handleCompact(Request request) throws IOException {
        dao.compact();
        return LSMConstantResponse.ok(request);
    }

    private static MemorySegment fromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(LSMConstantResponse.badRequest(request));
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new LSMCustomSession(socket, this);
    }
}
