package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpException;
import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;
import ru.vk.itmo.test.kovalevigor.dao.DaoImpl;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class Server extends HttpServer implements AutoCloseable, RejectedExecutionHandler {

    private static final String ENTITY = "/v0/entity";
    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private static final Logger log = Logger.getAnonymousLogger();

    private class Task implements Runnable {
        public final Request request;
        public final HttpSession session;

        private Task(Request request, HttpSession session) {
            this.request = request;
            this.session = session;
        }

        @Override
        public void run() {
            try {
                this.handleRequest(request, session);
            } catch (IOException ioException) {
                session.handleException(ioException);
            } catch (Exception exception) {
                Responses response = Responses.INTERNAL_ERROR;
                if (exception instanceof HttpException) {
                    response = Responses.BAD_REQUEST;
                } else {
                    log.log(Level.SEVERE, exception.getMessage());
                }
                sendResponseWithoutIo(session, response);
            }
        }

        private void handleRequest(Request request, HttpSession session) throws IOException {
            if (request.getPath().equals(ENTITY)) {
                switch (request.getMethod()) {
                    case METHOD_GET:
                    case METHOD_PUT:
                    case METHOD_DELETE:
                        String entityId = request.getParameter("id=");
                        if (entityId == null || entityId.isEmpty()) {
                            break;
                        }
                        MemorySegment key = fromString(entityId);
                        session.sendResponse(
                                switch (request.getMethod()) {
                                    case METHOD_GET -> getEntity(key);
                                    case METHOD_PUT -> createEntity(key, MemorySegment.ofArray(request.getBody()));
                                    case METHOD_DELETE -> deleteEntity(key);
                                    default -> throw new IllegalStateException("Can't be");
                                }
                        );
                        return;
                    default:
                        session.sendResponse(Responses.NOT_ALLOWED.toResponse());
                        return;
                }
            }
            session.sendResponse(Responses.BAD_REQUEST.toResponse());
        }
    }

    public Server(DaoServerConfig config) throws IOException {
        super(config);
        dao = new DaoImpl(mapConfig(config));
        executorService =  new ThreadPoolExecutor(
                config.corePoolSize,
                config.maximumPoolSize,
                config.keepAliveTime,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity),
                this
        );
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        executorService.execute(new Task(request, session));
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(Responses.BAD_REQUEST.toResponse());
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        sendResponseWithoutIo(((Task) r).session, Responses.SERVICE_UNAVAILABLE);
    }

    private static void sendResponseWithoutIo(HttpSession session, Responses response) {
        try {
            session.sendResponse(response.toResponse());
        } catch (IOException ioException) {
            session.handleException(ioException);
        }
    }

    private Response getEntity(MemorySegment key) {
        Entry<MemorySegment> entity = dao.get(key);
        if (entity == null) {
            return Responses.NOT_FOUND.toResponse();
        }
        return Response.ok(entity.value().toArray(ValueLayout.JAVA_BYTE));
    }

    private Response createEntity(MemorySegment key, MemorySegment value) {
        dao.upsert(makeEntry(key, value));
        return Responses.CREATED.toResponse();
    }

    private Response deleteEntity(MemorySegment key) {
        dao.upsert(makeEntry(key, null));
        return Responses.ACCEPTED.toResponse();
    }

    @Override
    public void close() throws IOException {
        executorService.close();
        dao.close();
    }

    private static Config mapConfig(DaoServerConfig config) {
        return new Config(
                config.basePath,
                config.flushThresholdBytes
        );
    }

    private static Entry<MemorySegment> makeEntry(MemorySegment key, MemorySegment value) {
        return new BaseEntry<>(key, value);
    }

    private static MemorySegment fromString(final String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(CHARSET));
    }
}
