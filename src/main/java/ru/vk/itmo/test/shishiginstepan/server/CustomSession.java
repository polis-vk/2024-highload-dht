package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;
import org.apache.log4j.Logger;
import ru.vk.itmo.test.shishiginstepan.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class CustomSession extends HttpSession {
    private static final byte[] keyValueDelimiter = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] chunkDelimiter = "\r\n".getBytes(StandardCharsets.UTF_8);
    private final byte[] chunkedHeader;
    private final Logger logger = Logger.getLogger("lsm-db-server-http-session");


    public CustomSession(Socket socket, HttpServer server) {

        super(socket, server);
        Response r = new Response(Response.OK);
        r.addHeader("Transfer-Encoding:chunked");
        chunkedHeader = r.toBytes(false);
    }

    ByteBuffer buffer;

    @Override
    public synchronized void sendError(String code, String message){
        try {
            super.sendError(code, message);
        } catch (IOException e) {
            logger.error(e);
            scheduleClose();
        }
    }

    @Override
    public synchronized void sendResponse(Response response){
        try {
            super.sendResponse(response);
        } catch (IOException e) {
            logger.error(e);
            sendError(Response.INTERNAL_ERROR, "error sending response");
        }
    }

    public void sendChunks(Iterator<EntryWithTimestamp<MemorySegment>> entries){
        buffer = ByteBuffer.allocate(1024);

        QueueItem headerItem = new QueueItem() {
            @Override
            public int write(Socket socket) throws IOException {
                return socket.write(chunkedHeader, 0, chunkedHeader.length);
            }

            @Override
            public void release() {
                this.next = new QueueItem() {
                    @Override
                    public int write(Socket socket) throws IOException {
                        EntryWithTimestamp<MemorySegment> entry = entries.next();
                        Integer chunkSize = (int) (entry.key().byteSize() + entry.value()
                                .byteSize() + keyValueDelimiter.length);
                        if (buffer.capacity() < chunkSize + chunkDelimiter.length * 2 + 4) {
                            buffer = ByteBuffer.allocate(chunkSize + chunkDelimiter.length * 2 + 4);
                        }
                        buffer.clear();

                        buffer.put(Integer.toHexString(chunkSize).getBytes(StandardCharsets.US_ASCII));
                        buffer.put(chunkDelimiter);
                        buffer.put(entry.key().toArray(ValueLayout.JAVA_BYTE));
                        buffer.put(keyValueDelimiter);
                        buffer.put(entry.value().toArray(ValueLayout.JAVA_BYTE));
                        buffer.put(chunkDelimiter);
                        if (remaining()==0) {
                            buffer.put("0".getBytes(StandardCharsets.US_ASCII));
                            buffer.put(chunkDelimiter);
                            buffer.put(chunkDelimiter);
                        }

                        return socket.write(buffer.array(), 0, buffer.position());
                    }

                    @Override
                    public int remaining() {
                        return entries.hasNext()? 1: 0;
                    }
                };
            }
        };

        try {
            write(headerItem);
        } catch (IOException e) {
            logger.error(e);
            sendError(Response.INTERNAL_ERROR, "error in streaming");
        }
        if ((this.handling = pipeline.pollFirst()) != null) {
            if (handling == FIN) {
                scheduleClose();
            } else {
                try {
                    server.handleRequest(handling, this);
                } catch (IOException e) {
                    logger.error(e);
                    sendError(Response.INTERNAL_ERROR, "error in streaming");
                }
            }
        }
    }
}
