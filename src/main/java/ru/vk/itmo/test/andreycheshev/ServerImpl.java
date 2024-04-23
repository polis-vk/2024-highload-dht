package ru.vk.itmo.test.andreycheshev;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.net.Socket;
import one.nio.server.RejectedSessionException;
import ru.vk.itmo.test.andreycheshev.dao.Dao;
import ru.vk.itmo.test.andreycheshev.dao.Entry;
import ru.vk.itmo.test.andreycheshev.dao.StreamingSession;
import ru.vk.itmo.test.reference.ReferenceHttpSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;

public class ServerImpl extends HttpServer {
    private final RequestExecutor executor;
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public ServerImpl(HttpServerConfig config,
                      Dao<MemorySegment, Entry<MemorySegment>> dao,
                      RequestExecutor executor) throws IOException {
        super(config);

        this.dao = dao;
        this.executor = executor;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) {
        executor.execute(request, session);
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new StreamingSession(socket, this);
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            executor.shutdown();
            dao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
