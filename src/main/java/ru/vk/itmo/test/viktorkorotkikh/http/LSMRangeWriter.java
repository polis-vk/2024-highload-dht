package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.util.ByteArrayBuilder;
import one.nio.util.Utf8;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.util.LsmServerUtil;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;

import static ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse.CHUNKED_RESPONSE_CLOSE_WITH_HEADERS_BYTES;
import static ru.vk.itmo.test.viktorkorotkikh.util.LSMConstantResponse.CHUNKED_RESPONSE_KEEP_ALIVE_WITH_HEADERS_BYTES;

public class LSMRangeWriter {
    private static final int BUFFER_SIZE = 8192;
    private static final int CHUNK_SIZE_BYTES = 16;
    private static final byte[] CRLF_BYTES = new byte[]{'\r', '\n'};
    private final Iterator<TimestampedEntry<MemorySegment>> entryIterator;
    private final boolean keepAlive;
    private final ByteArrayBuilder buffer;
    private long chunkSize = 0;
    private TimestampedEntry<MemorySegment> lastEntry;
    private int lastEntryOffset;
    private NextOperation nextOperation = NextOperation.WRITE_HEADERS;

    public LSMRangeWriter(Iterator<TimestampedEntry<MemorySegment>> entryIterator, boolean keepAlive) {
        this.entryIterator = entryIterator;
        this.keepAlive = keepAlive;
        this.buffer = new ByteArrayBuilder(BUFFER_SIZE);
    }

    public boolean hasChunks() {
        return entryIterator.hasNext() || nextOperation != null;
    }

    public Chunk nextChunk() {
        chunkSize = 0;
        int chunkSizeOffset = CHUNK_SIZE_BYTES + CRLF_BYTES.length;
        boolean writeHttpHeaders = false;
        if (nextOperation == NextOperation.WRITE_HEADERS) {
            chunkSizeOffset += keepAlive
                    ? CHUNKED_RESPONSE_KEEP_ALIVE_WITH_HEADERS_BYTES.length
                    : CHUNKED_RESPONSE_CLOSE_WITH_HEADERS_BYTES.length;
            writeHttpHeaders = true;
        }
        buffer.setLength(chunkSizeOffset);

        if (!appendNextOperationBytes()) {
            int chunkOffset = writeChunkHeader(chunkSizeOffset, writeHttpHeaders);
            return new Chunk(buffer, chunkOffset);
        }

        while (entryIterator.hasNext()) {
            lastEntry = entryIterator.next();
            lastEntryOffset = 0;
            if (buffer.length() + getEntrySize(lastEntry) + CRLF_BYTES.length > buffer.capacity()) {
                int offset = writeChunkHeader(chunkSizeOffset, writeHttpHeaders);
                return new Chunk(buffer, offset);
            }

            nextOperation = NextOperation.WRITE_KEY;
            if (!appendNextOperationBytes()) {
                int chunkOffset = writeChunkHeader(chunkSizeOffset, writeHttpHeaders);
                return new Chunk(buffer, chunkOffset);
            }
        }

        int chunkOffset = writeChunkHeader(chunkSizeOffset, writeHttpHeaders);
        if (chunkSize == 0) {
            appendCLRF(buffer);
        } else {
            buffer.append(Long.toHexString(0));
            appendCLRF(buffer);
            appendCLRF(buffer);
        }

        nextOperation = null;

        return new Chunk(buffer, chunkOffset);
    }

    private int writeChunkHeader(int chunkSizeOffset, boolean writeHttpHeaders) {
        String chunkSizeHexString = Long.toHexString(chunkSize);
        int chunkSizeHexStringLength = Utf8.length(chunkSizeHexString);
        int chunkSizeStart = chunkSizeOffset - chunkSizeHexStringLength - CRLF_BYTES.length;
        Utf8.write(chunkSizeHexString, buffer.buffer(), chunkSizeStart);

        System.arraycopy(
                CRLF_BYTES,
                0,
                buffer.buffer(),
                chunkSizeStart + chunkSizeHexStringLength,
                CRLF_BYTES.length
        );

        int headersOffset = 0;
        if (writeHttpHeaders) {
            byte[] headers = keepAlive
                    ? CHUNKED_RESPONSE_KEEP_ALIVE_WITH_HEADERS_BYTES
                    : CHUNKED_RESPONSE_CLOSE_WITH_HEADERS_BYTES;
            System.arraycopy(headers, 0, buffer.buffer(), chunkSizeStart - headers.length, headers.length);
            headersOffset = headers.length;
        }
        appendCLRF(buffer);
        return chunkSizeStart - headersOffset;
    }

    private boolean appendNextOperationBytes() {
        return switch (nextOperation) {
            case WRITE_HEADERS -> true; // we write headers later in writeChunkSize method
            case WRITE_KEY -> {
                if (buffer.length() + CRLF_BYTES.length >= buffer.capacity()) {
                    yield false;
                }

                if (appendMemorySegment(lastEntry.key(), lastEntryOffset, true)) {
                    chunkSize += lastEntry.key().byteSize() - lastEntryOffset;
                    nextOperation = NextOperation.WRITE_DELIMITERS_AFTER_KEY;
                    yield appendNextOperationBytes();
                }
                chunkSize += lastEntryOffset;
                yield false;
            }
            case WRITE_DELIMITERS_AFTER_KEY -> {
                if (buffer.length() + 1 + CRLF_BYTES.length <= buffer.capacity()) {
                    chunkSize += 1;
                    buffer.append('\n');
                    nextOperation = NextOperation.WRITE_VALUE;
                    yield appendNextOperationBytes();
                }
                yield false;
            }
            case WRITE_VALUE -> {
                if (buffer.length() + CRLF_BYTES.length >= buffer.capacity()) {
                    yield false;
                }
                if (appendMemorySegment(lastEntry.value(), lastEntryOffset, false)) {
                    chunkSize += lastEntry.value().byteSize() - lastEntryOffset;
                    nextOperation = null;
                    yield true;
                }
                chunkSize += lastEntryOffset;
                yield false;
            }
            case null -> true;
        };
    }

    private boolean appendMemorySegment(MemorySegment memorySegment, int memorySegmentOffset, boolean isKey) {
        int writtenMemorySegment = LsmServerUtil.copyMemorySegmentToByteArrayBuilder(
                memorySegment,
                memorySegmentOffset,
                buffer
        );

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
        WRITE_VALUE
    }
}
