package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.util.ByteArrayBuilder;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.util.LsmServerUtil;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

import static ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse.CHUNKED_RESPONSE_CLOSE_WITH_HEADERS_BYTES;
import static ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse.CHUNKED_RESPONSE_KEEP_ALIVE_WITH_HEADERS_BYTES;

public class LSMRangeWriter {
    private static final int BUFFER_SIZE = 8192;
    private static final byte[] CRLF_BYTES = new byte[]{'\r', '\n'};
    private final Iterator<TimestampedEntry<MemorySegment>> entryIterator;
    private final boolean keepAlive;
    private final ByteArrayBuilder buffer;
    private TimestampedEntry<MemorySegment> lastEntry;
    private int lastEntryOffset;
    private NextOperation nextOperation = NextOperation.WRITE_HEADERS;

    public LSMRangeWriter(Iterator<TimestampedEntry<MemorySegment>> entryIterator, boolean keepAlive) {
        this.entryIterator = entryIterator;
        this.keepAlive = keepAlive;
        this.buffer = new ByteArrayBuilder(BUFFER_SIZE);
    }

    public int remaining() {
        return entryIterator.hasNext() || nextOperation != null ? 1 : 0;
    }

    public ByteArrayBuilder nextChunk() {
        buffer.setLength(0);
        if (!appendNextOperationBytes()) {
            return buffer;
        }
        // write entries while iterator hasNext and buffer has enough capacity to write new chunk size
        while (entryIterator.hasNext() && buffer.length() <= BUFFER_SIZE - 18) { // 18 - 16 hex bytes + 2 \r\n bytes
            lastEntry = entryIterator.next();
            lastEntryOffset = 0;
            nextOperation = NextOperation.WRITE_KEY;
            if (!appendNextOperationBytes()) {
                return buffer;
            }
        }

        if (!entryIterator.hasNext()) {
            buffer.append(Long.toHexString(0));
            appendCLRF(buffer);
            appendCLRF(buffer);
        }

        return buffer;
    }

    private boolean appendNextOperationBytes() {
        return switch (nextOperation) {
            case WRITE_HEADERS -> {
                // we are sure that buffer has enough space to write headers
                byte[] responseBytes = keepAlive
                        ? CHUNKED_RESPONSE_KEEP_ALIVE_WITH_HEADERS_BYTES
                        : CHUNKED_RESPONSE_CLOSE_WITH_HEADERS_BYTES;
                buffer.append(responseBytes);
                yield true;
            }
            case WRITE_KEY -> {
                if (lastEntryOffset > 0 || buffer.length() > BUFFER_SIZE - 18) {
                    yield false;
                }

                buffer.append(Long.toHexString(getEntrySize(lastEntry)));
                appendCLRF(buffer);

                if (appendMemorySegment(lastEntry.key(), lastEntryOffset, true)) {
                    nextOperation = NextOperation.WRITE_DELIMITERS_AFTER_KEY;
                    yield appendNextOperationBytes();
                }
                yield false;
            }
            case WRITE_DELIMITERS_AFTER_KEY -> {
                if (buffer.length() <= BUFFER_SIZE - 1) {
                    buffer.append('\n');
                    nextOperation = NextOperation.WRITE_VALUE;
                    yield appendNextOperationBytes();
                }
                yield false;
            }
            case WRITE_VALUE -> {
                if (appendMemorySegment(lastEntry.value(), lastEntryOffset, false)) {
                    nextOperation = NextOperation.WRITE_DELIMITERS_AFTER_VALUE;
                    yield appendNextOperationBytes();
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

    private boolean appendMemorySegment(MemorySegment memorySegment, int memorySegmentOffset, boolean isKey) {
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
        WRITE_HEADERS,
        WRITE_KEY, WRITE_DELIMITERS_AFTER_KEY,
        WRITE_VALUE, WRITE_DELIMITERS_AFTER_VALUE
    }
}
