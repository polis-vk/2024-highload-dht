package ru.vk.itmo.test.viktorkorotkikh.dao.io.write;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Writer without compression. Instead of blocks, normal data is written: key1Size|key1|value1Size|value1
 *
 * @author vitekkor
 */
public final class BaseSSTableWriter extends AbstractSSTableWriter {
    private long entryOffset;

    @Override
    protected void writeIndexInfo(
            MemorySegment mappedIndexFile,
            int entriesSize,
            boolean hasNoTombstones
    ) {
        mappedIndexFile.set(ValueLayout.JAVA_BOOLEAN, 0, hasNoTombstones);
        mappedIndexFile.set(ValueLayout.JAVA_LONG_UNALIGNED, 1, entriesSize);
    }

    @Override
    protected void writeEntry(
            final Entry<MemorySegment> entry,
            final OutputStream os,
            final OutputStream indexStream
    ) throws IOException {
        final MemorySegment key = entry.key();
        final MemorySegment value = entry.value();
        long result = 0L;

        // Key size
        writeLong(key.byteSize(), os);
        result += Long.BYTES;

        // Key
        writeSegment(key, os);
        result += key.byteSize();

        // Value size and possibly value
        if (value == null) {
            // Tombstone
            writeLong(-1, os);
            result += Long.BYTES;
        } else {
            // Value length
            writeLong(value.byteSize(), os);
            result += Long.BYTES;

            // Value
            writeSegment(value, os);
            result += value.byteSize();
        }
        writeLong(entryOffset, indexStream);
        entryOffset += result;
    }

    private void writeLong(final long value, final OutputStream os) throws IOException {
        longBuffer.segment().set(ValueLayout.JAVA_LONG_UNALIGNED, 0, value);
        longBuffer.withArray(os::write);
    }

    private void writeSegment(final MemorySegment value, final OutputStream os) throws IOException {
        final long size = value.byteSize();
        blobBuffer.ensureCapacity(size);
        MemorySegment.copy(value, 0L, blobBuffer.segment(), 0L, size);
        blobBuffer.withArray(array -> os.write(array, 0, (int) size));
    }
}
