package ru.vk.itmo.test.dariasupriadkina;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.net.Socket;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;

public class CustomHttpSession extends HttpSession {

    private Iterator<ExtendedEntry<MemorySegment>> iterator;
    private ByteArrayBuilder builder;

    public CustomHttpSession(Socket socket, HttpServer server) {
        super(socket, server);
    }


    @Override
    protected void processWrite() throws Exception {
        super.processWrite();

        sendNextChunkScope(builder == null ? new ByteArrayBuilder() : builder);
    }

    public void streaming(Iterator<ExtendedEntry<MemorySegment>> iterator) throws IOException {
        this.iterator = iterator;
        this.builder = new ByteArrayBuilder();

        write(EntryChunkUtils.HEADER_BYTES, 0, EntryChunkUtils.HEADER_BYTES.length);
        sendNextChunkScope(builder);
    }

    private void sendNextChunkScope(ByteArrayBuilder builder) throws IOException {
        while (iterator.hasNext() && queueHead == null) {
            ExtendedEntry<MemorySegment> ee = iterator.next();
            EntryChunkUtils.getEntryByteChunk(ee, builder);
            write(builder.buffer(), 0, builder.length());
            builder.setLength(0);
        }
        if (!iterator.hasNext()) {
            write(EntryChunkUtils.LAST_BYTES, 0, EntryChunkUtils.LAST_BYTES.length);
        }
        scheduleClose();
    }


}
