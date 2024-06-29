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

            private final byte[][] parts;
            protected int offset;
            public final int total;

            public Chunk() {
                this.parts = new byte[3][];
                this.parts[0] = ZERO_CHUNK_LENGTH;
                this.parts[1] = CHUNK_SEPARATOR;
                this.parts[2] = CHUNK_SEPARATOR;
                this.total = ZERO_CHUNK_LENGTH.length + 2 * CHUNK_SEPARATOR.length;
                this.offset = 0;
            }

            public Chunk(final TimeEntry<MemorySegment> entry) {
                this.parts = new byte[6][];
                this.parts[2] = entry.key().toArray(ValueLayout.JAVA_BYTE);
                this.parts[3] = ENTRY_SEPARATOR;
                this.parts[4] = entry.value().toArray(ValueLayout.JAVA_BYTE);
                final int chunkLength = parts[2].length + parts[3].length + parts[4].length;
                this.parts[0] = encodeChunkLength(chunkLength);
                this.parts[1] = CHUNK_SEPARATOR;
                this.parts[5] = CHUNK_SEPARATOR;
                this.total = parts[0].length + 2 * CHUNK_SEPARATOR.length + chunkLength;
                this.offset = 0;
            }

            public int write(final Socket socket) throws IOException {

                final int beforeOffset = offset;
                int curLength = 0;
                for (final byte[] arr: parts) {
                    if (!tryFull(curLength, arr, socket)) {
                        break;
                    }
                    curLength += arr.length;
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
