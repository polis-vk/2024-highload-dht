package ru.vk.itmo.test.andreycheshev.dao;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.net.Socket;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class StreamingSession extends HttpSession {
    private static final int BATCH_SIZE = 1024 * 1024; // 1 mb.


    public StreamingSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    public void stream(Iterator<Entry<MemorySegment>> streamIterator) {
//        for (Entry)
    }

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();
    }
}
