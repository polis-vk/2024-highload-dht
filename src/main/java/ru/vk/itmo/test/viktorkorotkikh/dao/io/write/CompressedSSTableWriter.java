package ru.vk.itmo.test.viktorkorotkikh.dao.io.write;

import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.dao.compressor.Compressor;
import ru.vk.itmo.test.viktorkorotkikh.dao.compressor.LZ4Compressor;
import ru.vk.itmo.test.viktorkorotkikh.dao.compressor.ZstdCompressor;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Writer implementation with compression.
 * Implementations of the {@link Compressor} interface are used for compression.
 * <br/>
 * <br/>
 * <B>compression info</B>:
 * isCompressed|algorithm|blocksCount|uncompressedBlockSize|block1Offset|block2Offset|blockNOffset
 * <p/>
 * <B>index</B>:
 * hasNoTombstones|entriesSize|key1BlockNumber|key1SizeBlockOffset|key2BlockNumber|key2SizeBlockOffset| ...
 * |keyNBlockNumber|keyNSizeBlockOffset|
 * <p/>
 * keyNBlockNumber - block number to start key number N (key1Size|key1|value1Size|value1)
 * <br/>
 * keyNSizeBlockOffset - offset of the start of the key size inside the block
 * <p/>
 * <B>blocks</B>:
 * block1|block2|...|blockN
 *
 * @author vitekkor
 */
public final class CompressedSSTableWriter extends AbstractSSTableWriter {

    private final Compressor compressor;
    private int blobBufferOffset;
    private int blockCount;
    private int blockOffset;

    public CompressedSSTableWriter(Compressor compressor, int blockSize) {
        super(blockSize);
        this.compressor = compressor;
    }

    @Override
    protected void writeIndexInfo(
            MemorySegment mappedIndexFile,
            MemorySegment mappedCompressionInfoFile,
            int entriesSize,
            boolean hasNoTombstones
    ) {
        // hasNoTombstones|entriesSize
        mappedIndexFile.set(ValueLayout.JAVA_BOOLEAN, 0, hasNoTombstones);
        mappedIndexFile.set(ValueLayout.JAVA_LONG_UNALIGNED, 1, entriesSize);

        // blocksCount|uncompressedBlockSize
        mappedCompressionInfoFile.set(ValueLayout.JAVA_INT_UNALIGNED, 0, blockCount);
        mappedCompressionInfoFile.set(ValueLayout.JAVA_INT_UNALIGNED, Integer.BYTES, blockSize);
    }

    @Override
    protected void writeEntry(
            final TimestampedEntry<MemorySegment> entry,
            final OutputStream os,
            final OutputStream compressionInfoStream,
            final OutputStream indexStream
    ) throws IOException {
        // write index
        writeInt(indexStream, blockCount); // keyNBlockNumber
        writeInt(indexStream, blobBufferOffset); // keyNSizeBlockOffset

        final MemorySegment key = entry.key();
        final long keySize = key.byteSize();
        // write keyNSize
        writeLong(keySize, os, compressionInfoStream);

        // write key
        writeMemorySegment(key, os, compressionInfoStream);

        final MemorySegment value = entry.value();
        final long valueSize;
        if (value == null) {
            valueSize = -1;
        } else {
            valueSize = value.byteSize();
        }

        // write value size
        writeLong(valueSize, os, compressionInfoStream);

        if (value != null) {
            // write value
            writeMemorySegment(value, os, compressionInfoStream);
        }
        writeLong(entry.timestamp(), os, compressionInfoStream);
        flush(os, compressionInfoStream);
    }

    private static void writeInt(OutputStream outputStream, int value) throws IOException {
        outputStream.write((byte) value);
        outputStream.write((byte) (value >> 8));
        outputStream.write((byte) (value >> 16));
        outputStream.write((byte) (value >> 24));
    }

    /**
     * Flush blobBuffer to outputStream if blobBufferOffset >= blockSize.
     *
     * @param os outputStream for writing
     * @throws IOException if an I/O error occurs.
     */
    private void flush(OutputStream os, OutputStream compressionInfoStream) throws IOException {
        if (blobBufferOffset >= blockSize) {
            byte[] compressed = blobBuffer.withArrayReturn(compressor::compress);
            os.write(compressed);
            blobBufferOffset = 0;
            blockCount++;
            writeInt(compressionInfoStream, blockOffset); // blockNOffset
            blockOffset += compressed.length;
        }
    }

    @Override
    protected void finish(OutputStream os, OutputStream compressionInfoStream) throws IOException {
        byte[] compressed = blobBuffer.withArrayReturn(src -> compressor.compress(src, blobBufferOffset));
        os.write(compressed);
        writeInt(compressionInfoStream, blockOffset);

        blobBufferOffset = compressor.calculateLastBlockOffset(blobBufferOffset, compressed);
        writeInt(compressionInfoStream, blobBufferOffset); // size of last uncompressed data
        
        blobBufferOffset = 0;
        blockCount++;
        blockOffset += compressed.length;
    }

    private void writeLong(
            final long value,
            OutputStream os,
            OutputStream compressionInfoStream
    ) throws IOException {
        longBuffer.segment().set(ValueLayout.JAVA_LONG_UNALIGNED, 0, value);
        int longBytesIndex = 0;
        while (longBytesIndex < Long.BYTES) {
            int index = blobBufferOffset;

            int finalLongBytesIndex = longBytesIndex;
            byte longByte = longBuffer.withArrayReturn(longBytes -> longBytes[finalLongBytesIndex]);

            blobBuffer.withArray(array -> array[index] = longByte);
            blobBufferOffset++;
            longBytesIndex++;
            flush(os, compressionInfoStream);
        }
    }

    /**
     * Write memorySegment to blobBuffer and flush it if necessary to os.
     *
     * @param memorySegment memorySegment to write
     * @param os            outputStream to flush
     * @throws IOException if an I/O error occurs.
     */
    private void writeMemorySegment(
            final MemorySegment memorySegment,
            final OutputStream os,
            final OutputStream compressionInfoStream
    ) throws IOException {
        // write memory segment
        final long memorySegmentSize = memorySegment.byteSize();
        long writtenMemorySegmentBytes = 0L;
        // loop until the entire memory segment has been written
        while (writtenMemorySegmentBytes < memorySegmentSize) {
            long bytes;
            // calc bytes size to write
            int localBufferOffset = blobBufferOffset;
            if (blobBufferOffset + memorySegmentSize - writtenMemorySegmentBytes <= blockSize) {
                // if the remaining data fits within the current blob buffer, write the rest of the memory segment
                bytes = memorySegmentSize - writtenMemorySegmentBytes;
                blobBufferOffset += (int) bytes;
            } else {
                // if the remaining data exceeds the current blob buffer capacity, fill the buffer
                bytes = (long) blockSize - blobBufferOffset;
                blobBufferOffset = blockSize;
            }

            MemorySegment.copy(
                    memorySegment,
                    writtenMemorySegmentBytes,
                    blobBuffer.segment(),
                    localBufferOffset,
                    bytes
            );

            writtenMemorySegmentBytes += bytes;

            flush(os, compressionInfoStream);
        }
    }

    @Override
    protected void writeCompressionHeader(OutputStream os) throws IOException {
        os.write(1); // isCompressed == true
        // algorithm: 0 - LZ4; 1 - ZSTD
        if (compressor instanceof LZ4Compressor) { // switch-case codeclimate error
            os.write(0);
        } else if (compressor instanceof ZstdCompressor) {
            os.write(1);
        } else {
            throw new IllegalStateException("Unexpected value: " + compressor);
        }
    }
}
