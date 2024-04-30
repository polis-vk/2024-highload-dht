package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.net.Socket;
import ru.vk.itmo.test.smirnovdmitrii.dao.TimeEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class DaoSession extends HttpSession {
    private static final byte[] CHUNKED_HEADERS =
            """
            HTTP/1.1 200\r
            OKContentType: application/octet-stream\r
            Transfer-Encoding: chunked\r
            Connection: keep-alive\r
            \r
            """.getBytes(StandardCharsets.UTF_8);
    private static final byte[] ZERO_CHUNK_LENGTH = encodeChunkLength(0);
    private static final byte[] CHUNK_SEPARATOR = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ENTRY_SEPARATOR = "\n".getBytes(StandardCharsets.UTF_8);

    public DaoSession(final Socket socket, final HttpServer server) {
        super(socket, server);
    }

    public void range(final Iterator<TimeEntry<MemorySegment>> iterator) throws IOException {
        write(CHUNKED_HEADERS, 0, CHUNKED_HEADERS.length);
        write(new RangeQueueItem(iterator));
    }

    private static class RangeQueueItem extends QueueItem {

        private Chunk chunk;
        private boolean isEnd = false;
        private final Chunk lastChunk = new Chunk();

        private static class Chunk {
            private final byte[] key;
            private final byte[] value;
            protected final byte[] chunkLength;
            protected int offset;
            public final int total;

            public Chunk() {
                this.chunkLength = ZERO_CHUNK_LENGTH;
                this.total = chunkLength.length + 2 * CHUNK_SEPARATOR.length;
                this.key = null;
                this.value = null;
                this.offset = 0;
            }

            public Chunk(final TimeEntry<MemorySegment> entry) {
                final byte[] key = entry.key().toArray(ValueLayout.JAVA_BYTE);
                this.key = key;
                final byte[] value = entry.value().toArray(ValueLayout.JAVA_BYTE);
                this.value = value;
                this.chunkLength = encodeChunkLength(ENTRY_SEPARATOR.length + key.length + value.length);
                this.total = this.chunkLength.length + 2 * CHUNK_SEPARATOR.length
                        + key.length + value.length + ENTRY_SEPARATOR.length;
                this.offset = 0;
            }

            public int write(final Socket socket) throws IOException {
                // chunk length
                // chunk separator
                // key
                // entry separator
                // value
                // chunk separator
                final int beforeOffset = offset;
                int curLength = 0;
                if (tryFull(curLength, chunkLength, socket)) {
                    curLength += chunkLength.length;
                    if (tryFull(curLength, CHUNK_SEPARATOR, socket)) {
                        curLength += CHUNK_SEPARATOR.length;
                        if (key == null || tryFull(curLength, key, socket)) {
                            if (key != null) {
                                curLength += key.length;
                            }
                            if (key == null || tryFull(curLength, ENTRY_SEPARATOR, socket)) {
                                if (key != null) {
                                    curLength += ENTRY_SEPARATOR.length;
                                }
                                if (value == null || tryFull(curLength, value, socket)) {
                                    if (value != null) {
                                        curLength += value.length;
                                    }
                                    tryFull(curLength, CHUNK_SEPARATOR, socket);
                                }
                            }
                        }
                    }
                }
                return offset - beforeOffset;
            }

            private boolean tryFull(
                    int offsetOffset,
                    byte[] array,
                    Socket socket
            ) throws IOException {
                if (offset - offsetOffset < array.length) {
                    final int countToWrite = array.length - (offset - offsetOffset);
                    if (countToWrite == 0) {
                        return true;
                    }

                    offset += socket.write(array, offset - offsetOffset, countToWrite);
                }
                return (offset - offsetOffset) >= array.length;
            }

        }

        private final Iterator<TimeEntry<MemorySegment>> iterator;

        private RangeQueueItem(Iterator<TimeEntry<MemorySegment>> iterator) {
            this.iterator = iterator;
        }

        @Override
        public int remaining() {
            return isEnd ? 0 : 1;
        }

        @Override
        public int write(final Socket socket) throws IOException {
            int total = 0;
            System.out.println(Thread.currentThread().getThreadGroup());
            while (true) {
                if (!iterator.hasNext() && chunk == null) {
                    final int written = lastChunk.write(socket);
                    if (lastChunk.offset == lastChunk.total) {
                        isEnd = true;
                    }
                    return total + written;
                }
                if (chunk == null) {
                    chunk = new Chunk(iterator.next());
                }
                total += chunk.write(socket);
                if (chunk.offset == chunk.total) {
                    chunk = null;
                } else {
                    return total;
                }
            }
        }
    }

    private static byte[] encodeChunkLength(final int length) {
        return Integer.toHexString(length).toUpperCase().getBytes(StandardCharsets.UTF_8);
    }
}
