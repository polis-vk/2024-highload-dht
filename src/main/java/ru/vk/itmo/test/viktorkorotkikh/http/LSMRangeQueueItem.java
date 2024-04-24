package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.net.Session;
import one.nio.net.Socket;
import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.util.LsmServerUtil;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;

import static ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse.CHUNKED_RESPONSE_CLOSE_WITH_HEADERS_BYTES;
import static ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse.CHUNKED_RESPONSE_KEEP_ALIVE_WITH_HEADERS_BYTES;

public class LSMRangeQueueItem extends Session.QueueItem {
    private static final int BUFFER_SIZE = 8192;
    private static final byte[] CRLF_BYTES = new byte[]{'\r', '\n'};
    private final Iterator<TimestampedEntry<MemorySegment>> entryIterator;
    private final boolean keepAlive;
    private final ByteArrayBuilder buffer;
    private boolean headersAreWritten;
    private TimestampedEntry<MemorySegment> lastEntry;
    private int lastEntryOffset;
    private NextOperation nextOperation;

    public LSMRangeQueueItem(Iterator<TimestampedEntry<MemorySegment>> entryIterator, boolean keepAlive) {
        this.entryIterator = entryIterator;
        this.keepAlive = keepAlive;
        this.buffer = new ByteArrayBuilder(BUFFER_SIZE);
        this.headersAreWritten = false;
    }

    @Override
    public int remaining() {
        return entryIterator.hasNext() || nextOperation != null ? 1 : 0;
    }

    @Override
    public int write(Socket socket) throws IOException {
        if (!headersAreWritten) {
            byte[] responseBytes = keepAlive
                    ? CHUNKED_RESPONSE_KEEP_ALIVE_WITH_HEADERS_BYTES
                    : CHUNKED_RESPONSE_CLOSE_WITH_HEADERS_BYTES;
            buffer.append(responseBytes);
            headersAreWritten = true;
        }
        if (!writeLastEntry()) {
            return writeBufferToSocket(socket);
        }
        // write entries while iterator hasNext and buffer has enough capacity to write new chunk size
        while (entryIterator.hasNext() && buffer.length() <= BUFFER_SIZE - 18) { // 18 - 16 hex bytes + 2 \r\n bytes
            lastEntry = entryIterator.next();
            lastEntryOffset = 0;
            nextOperation = NextOperation.WRITE_KEY;
            if (!writeLastEntry()) {
                return writeBufferToSocket(socket);
            }
        }

        if (!entryIterator.hasNext()) {
            buffer.append(Long.toHexString(0));
            appendCLRF(buffer);
            appendCLRF(buffer);
        }

        return writeBufferToSocket(socket);
    }

    private int writeBufferToSocket(Socket socket) throws IOException {
        socket.writeFully(buffer.buffer(), 0, buffer.length());
        int written = buffer.length();
        buffer.setLength(0);

        return written;
    }

    private boolean writeLastEntry() {
        return switch (nextOperation) {
            case WRITE_KEY -> {
                if (lastEntryOffset > 0 || buffer.length() > BUFFER_SIZE - 18) {
                    yield false;
                }

                buffer.append(Long.toHexString(getEntrySize(lastEntry)));
                appendCLRF(buffer);

                if (writeMemorySegment(lastEntry.key(), lastEntryOffset, true)) {
                    nextOperation = NextOperation.WRITE_DELIMITERS_AFTER_KEY;
                    yield writeLastEntry();
                }
                yield false;
            }
            case WRITE_DELIMITERS_AFTER_KEY -> {
                if (buffer.length() <= BUFFER_SIZE - 1) {
                    buffer.append('\n');
                    nextOperation = NextOperation.WRITE_VALUE;
                    yield writeLastEntry();
                }
                yield false;
            }
            case WRITE_VALUE -> {
                if (writeMemorySegment(lastEntry.value(), lastEntryOffset, false)) {
                    nextOperation = NextOperation.WRITE_DELIMITERS_AFTER_VALUE;
                    yield writeLastEntry();
                }
                yield false;
            }
            case WRITE_DELIMITERS_AFTER_VALUE -> {
                if (buffer.length() <= BUFFER_SIZE - 2) {
                    appendCLRF(buffer);
                    nextOperation = null;
                    yield true;
                }
                yield false;
            }
            case null -> true;
        };
    }

    private boolean writeMemorySegment(MemorySegment memorySegment, int memorySegmentOffset, boolean isKey) {
        int writtenMemorySegment
                = LsmServerUtil.copyMemorySegmentToByteArrayBuilder(memorySegment, memorySegmentOffset, buffer);
        if (writtenMemorySegment < memorySegment.byteSize()) {
            nextOperation = isKey ? NextOperation.WRITE_KEY : NextOperation.WRITE_VALUE;
            lastEntryOffset = writtenMemorySegment;
            return false;
        }
        lastEntryOffset = 0;
        return true;
    }

    private static void appendCLRF(final ByteArrayBuilder builder) {
        builder.append(CRLF_BYTES);
    }

    private static long getEntrySize(TimestampedEntry<MemorySegment> entry) {
        return entry.key().byteSize() + 1 + entry.value().byteSize(); // + 1 delimiter byte
    }

    private enum NextOperation {
        WRITE_KEY, WRITE_DELIMITERS_AFTER_KEY,
        WRITE_VALUE, WRITE_DELIMITERS_AFTER_VALUE
    }
}
