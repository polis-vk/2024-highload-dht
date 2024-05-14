package ru.vk.itmo.test.viktorkorotkikh.dao.io.read;

import ru.vk.itmo.test.viktorkorotkikh.dao.LSMPointerIterator;
import ru.vk.itmo.test.viktorkorotkikh.dao.MemorySegmentComparator;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.dao.Utils;
import ru.vk.itmo.test.viktorkorotkikh.dao.decompressor.Decompressor;
import ru.vk.itmo.test.viktorkorotkikh.dao.decompressor.LZ4Decompressor;
import ru.vk.itmo.test.viktorkorotkikh.dao.decompressor.ZstdDecompressor;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.UnexpectedIOException;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.UnknownCompressorTypeException;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.ByteArraySegment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.NoSuchElementException;

/**
 * SSTable reader implementation with compression.
 * Implementations of the {@link Decompressor} interface are used for decompression.
 * <br/>
 * <br/>
 * <B>compression info</B>:
 * isCompressed|algorithm|blocksCount|uncompressedBlockSize|block1Offset|block2Offset|blockNOffset
 * <p/>
 * <B>index</B>:
 * hasNoTombstones|entriesSize|key1BlockNumber|key1SizeBlockOffset|key2BlockNumber|key2SizeBlockOffset| ...
 * |keyNBlockNumber|keyNSizeBlockOffset|
 * <p/>
 * keyNBlockNumber - номер блока для начала ключа номер N (key1Size|key1|value1Size|value1)
 * <br/>
 * keyNSizeBlockOffset - смещение начала размера ключа внутри блока
 * <p/>
 * <B>blocks</B>:
 * block1|block2|...|blockN
 */
public class CompressedSSTableReader extends AbstractSSTableReader {
    private final int uncompressedBlockSize;
    private final int blocksCount;
    private final Decompressor decompressor;

    // hasNoTombstones|entriesSize|key1BlockNumber|key1SizeBlockOffset|...|keyNBlockNumber|keyNSizeBlockOffset|
    //       1b            4b            4b                 4b                   4b               4b
    private static final long INDEX_FILE_METADATA_OFFSET = 1L + Long.BYTES;
    private static final long KEY_SIZE_BLOCK_OFFSET = 2L * Integer.BYTES;

    private static final ScopedValue<ByteArraySegment> blockBuffer = ScopedValue.newInstance();
    private static final ScopedValue<ByteArraySegment> uncompressedBlockBuffer = ScopedValue.newInstance();

    /**
     * Help info about last decompressed block.
     */
    private static final class LastUncompressedBlockInfo {
        int lastUncompressedBlockNumber = -1;
        int lastUncompressedBlockOffset = -1;
    }

    private static final ScopedValue<LastUncompressedBlockInfo> lastUncompressedBlockInfo = ScopedValue.newInstance();

    public CompressedSSTableReader(
            MemorySegment mappedSSTable,
            MemorySegment mappedIndexFile,
            MemorySegment mappedCompressionInfo,
            Decompressor decompressor,
            int index
    ) {
        super(mappedSSTable, mappedIndexFile, mappedCompressionInfo, index);
        // isCompressed|algorithm|blocksCount|uncompressedBlockSize
        //     1b      |    1b   |     4b    |        4b
        this.uncompressedBlockSize = mappedCompressionInfo.get(ValueLayout.JAVA_INT_UNALIGNED, 2L + Integer.BYTES);
        this.blocksCount = mappedCompressionInfo.get(ValueLayout.JAVA_INT_UNALIGNED, 2L);

        this.decompressor = decompressor;
    }

    @Override
    public LSMPointerIterator iterator(MemorySegment from, MemorySegment to) throws IOException {
        int fromPosition = 0;
        int toPosition = (int) getEntriesSize() - 1;
        ByteArraySegment iteratorBlockBuffer = null;
        ByteArraySegment iteratorUncompressedBuffer = null;
        LastUncompressedBlockInfo iteratorLastUncompressedBlockInfo = null;
        if (from != null) {
            // optimization - if the entire record is in a block,
            // then the first time we call next we don’t need to read and decompress the data again
            iteratorBlockBuffer = getBuffer();
            iteratorUncompressedBuffer = getBuffer();
            iteratorLastUncompressedBlockInfo = new LastUncompressedBlockInfo();
            try {
                fromPosition = ScopedValue
                        .where(blockBuffer, iteratorBlockBuffer)
                        .where(uncompressedBlockBuffer, iteratorUncompressedBuffer)
                        .where(lastUncompressedBlockInfo, iteratorLastUncompressedBlockInfo)
                        .call(() -> (int) getEntryOffset(from, SearchOption.GTE));
            } catch (Exception e) {
                throw new IOException(e);
            }

            if (fromPosition == -1) {
                return new LSMPointerIterator.Empty(index);
            }
        }
        if (to != null) {
            ByteArraySegment toBlockBuffer = getBuffer();
            ByteArraySegment toUncompressedBuffer = getBuffer();
            LastUncompressedBlockInfo toLastUncompressedBlockInfo = new LastUncompressedBlockInfo();
            try {
                toPosition = ScopedValue
                        .where(blockBuffer, toBlockBuffer)
                        .where(uncompressedBlockBuffer, toUncompressedBuffer)
                        .where(lastUncompressedBlockInfo, toLastUncompressedBlockInfo)
                        .call(() -> (int) getEntryOffset(to, SearchOption.LT));
            } catch (Exception e) {
                throw new IOException(e);
            }

            if (toPosition == -1) {
                return new LSMPointerIterator.Empty(index);
            }
        }
        if (from == null) {
            iteratorBlockBuffer = getBuffer();
            iteratorUncompressedBuffer = getBuffer();
            iteratorLastUncompressedBlockInfo = new LastUncompressedBlockInfo();
        }

        return new CompressedSSTableIterator(
                fromPosition,
                toPosition,
                iteratorBlockBuffer,
                iteratorUncompressedBuffer,
                iteratorLastUncompressedBlockInfo
        );
    }

    private ByteArraySegment getBuffer() {
        return new ByteArraySegment(uncompressedBlockSize); // uncompressedBlockSize is maxSize of compressed data
    }

    private int getBlockOffset(int blockNumber) {
        return mappedCompressionInfo.get(
                ValueLayout.JAVA_INT_UNALIGNED,
                // isCompressed|algorithm|blocksCount|uncompressedBlockSize|blockNOffset|
                //     1b      |    1b   |     4b    |        4b           |      4b    |
                (1L + 1L + Integer.BYTES + Integer.BYTES + (long) blockNumber * Integer.BYTES)
        );
    }

    /**
     * Decompresses a single block of data from a source segment into a destination array.
     *
     * @param blockBuffer      A ByteArraySegment representing a buffer to hold the compressed data.
     * @param srcOffset        The offset in the source segment where the compressed data begins.
     * @param dest             The byte array where the decompressed data will be stored.
     * @param compressedSize   The size of the compressed data in bytes.
     * @param uncompressedSize The expected size of the decompressed data in bytes.
     * @throws IOException If an I/O error occurs during decompression.
     */
    private void decompressOneBlock(
            ByteArraySegment blockBuffer,
            long srcOffset,
            byte[] dest,
            int compressedSize,
            int uncompressedSize
    ) throws IOException {
        // copy compressed data into buffer
        MemorySegment.copy(
                mappedSSTable,
                srcOffset,
                blockBuffer.segment(),
                0L,
                compressedSize
        );
        // decompress from blockBuffer into dest
        blockBuffer.withArray(array ->
                decompressor.decompress(array, dest, 0, uncompressedSize, compressedSize)
        );
    }

    @Override
    protected TimestampedEntry<MemorySegment> getByIndex(long index) throws IOException {
        return readEntry((int) index, true);
    }

    @Override
    public TimestampedEntry<MemorySegment> get(MemorySegment key) throws IOException {
        try {
            return ScopedValue
                    .where(blockBuffer, getBuffer())
                    .where(uncompressedBlockBuffer, getBuffer())
                    .where(lastUncompressedBlockInfo, new LastUncompressedBlockInfo())
                    .call(() -> super.get(key));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Reads an entry based on the provided index and optionally reads the value.
     *
     * @param index     The index of the entry to read.
     * @param readValue A boolean flag indicating whether to read the value associated with the entry.
     * @return An Entry object containing the memory segment for the key and, if requested, the value.
     * @throws IOException If an I/O error occurs while reading the entry.
     */
    private TimestampedEntry<MemorySegment> readEntry(int index, boolean readValue) throws IOException {
        // retrieve the block number and offset for the key size from the index file
        int keyNBlockNumber = mappedIndexFile.get(
                ValueLayout.JAVA_INT_UNALIGNED,
                INDEX_FILE_METADATA_OFFSET + KEY_SIZE_BLOCK_OFFSET * index
        );
        int keyNSizeBlockOffset = mappedIndexFile.get(
                ValueLayout.JAVA_INT_UNALIGNED,
                INDEX_FILE_METADATA_OFFSET + KEY_SIZE_BLOCK_OFFSET * index + Integer.BYTES
        );

        // read and decompress the key size.
        byte[] decompressedKeySize = new byte[Long.BYTES];
        readCompressed(keyNBlockNumber, blockBuffer.get(), decompressedKeySize, keyNSizeBlockOffset);

        long keySize = byteArrayToLong(decompressedKeySize);

        // read and decompress the key
        ByteArraySegment keyByteArray = new ByteArraySegment((int) keySize);
        readCompressedByteArraySegment(
                lastUncompressedBlockInfo.get().lastUncompressedBlockNumber,
                blockBuffer.get(),
                keyByteArray,
                lastUncompressedBlockInfo.get().lastUncompressedBlockOffset
        );

        if (!readValue) {
            return new TimestampedEntry<>(keyByteArray.segment(), null, -1);
        }

        // read and decompress the value size
        byte[] decompressedValueSize = new byte[Long.BYTES];
        readCompressed(
                lastUncompressedBlockInfo.get().lastUncompressedBlockNumber,
                blockBuffer.get(),
                decompressedValueSize,
                lastUncompressedBlockInfo.get().lastUncompressedBlockOffset
        );
        long valueSize = byteArrayToLong(decompressedValueSize);

        if (valueSize == -1) {
            // read and decompress the timestamp
            byte[] decompressedTimestamp = new byte[Long.BYTES];
            readCompressed(
                    lastUncompressedBlockInfo.get().lastUncompressedBlockNumber,
                    blockBuffer.get(),
                    decompressedTimestamp,
                    lastUncompressedBlockInfo.get().lastUncompressedBlockOffset
            );
            long timestamp = byteArrayToLong(decompressedTimestamp);
            return new TimestampedEntry<>(keyByteArray.segment(), null, timestamp);
        } else {
            // read and decompress the value
            ByteArraySegment valueByteArray = new ByteArraySegment((int) valueSize);
            readCompressedByteArraySegment(
                    lastUncompressedBlockInfo.get().lastUncompressedBlockNumber,
                    blockBuffer.get(),
                    valueByteArray,
                    lastUncompressedBlockInfo.get().lastUncompressedBlockOffset
            );

            // read and decompress the timestamp
            byte[] decompressedTimestamp = new byte[Long.BYTES];
            readCompressed(
                    lastUncompressedBlockInfo.get().lastUncompressedBlockNumber,
                    blockBuffer.get(),
                    decompressedTimestamp,
                    lastUncompressedBlockInfo.get().lastUncompressedBlockOffset
            );
            long timestamp = byteArrayToLong(decompressedTimestamp);
            return new TimestampedEntry<>(keyByteArray.segment(), valueByteArray.segment(), timestamp);
        }
    }

    private void readCompressedByteArraySegment(
            int startBlockNumber,
            ByteArraySegment blockBuffer,
            ByteArraySegment targetDecompressedByteArraySegment,
            int blockOffset
    ) throws IOException {
        targetDecompressedByteArraySegment.withArray(targetDecompressedByteArray ->
                readCompressed(startBlockNumber, blockBuffer, targetDecompressedByteArray, blockOffset)
        );
    }

    /**
     * Reads compressed data starting from a specified block, decompresses it, and copies it into a target byte array.
     *
     * <p>This method handles the reading of compressed data from one or more blocks, decompressing it,
     * and copying the resulting decompressed data into the provided target byte array. It accounts for
     * cases where the target data spans across multiple blocks. The method updates the last uncompressed
     * block information as it proceeds through the blocks.</p>
     *
     * @param startBlockNumber            The block number from which to start reading the compressed data.
     * @param blockBuffer                 The ByteArraySegment used as a buffer for reading compressed data.
     * @param targetDecompressedByteArray The target byte array where the decompressed data will be copied.
     * @param blockOffset                 The offset within the block from which to start reading.
     * @throws IOException If an I/O error occurs during the read or decompression process.
     */
    private void readCompressed(
            int startBlockNumber,
            ByteArraySegment blockBuffer,
            byte[] targetDecompressedByteArray,
            int blockOffset
    ) throws IOException {
        // ====================================================================
        //               BLOCK
        // |................................|
        //  ^_____DATA_____^
        // uncompressedBlockSize - lastUncompressedBlockOffset >= targetDecompressedByteArray.length
        // so we should copy only DATA.length
        // ====================================================================
        //               BLOCK                              BLOCK
        // |................................||................................|
        //                            ^_____DATA_____^
        // uncompressedBlockSize - lastUncompressedBlockOffset < targetDecompressedByteArray.length
        // so we should copy tail of startBlockNumber and part of the next block(s)
        // ====================================================================

        int decompressedAndCopiedLength = 0;
        LastUncompressedBlockInfo localLastUncompressedBlockInfo = lastUncompressedBlockInfo.get();
        while (decompressedAndCopiedLength < targetDecompressedByteArray.length) {
            if (localLastUncompressedBlockInfo.lastUncompressedBlockNumber == startBlockNumber
                    && localLastUncompressedBlockInfo.lastUncompressedBlockOffset < uncompressedBlockSize) {
                // read tail from the last uncompressed block
                int length = readFromDecompressedBlock(
                        targetDecompressedByteArray,
                        decompressedAndCopiedLength,
                        blockOffset
                );
                decompressedAndCopiedLength += length;
                localLastUncompressedBlockInfo.lastUncompressedBlockOffset = blockOffset + length;
                // if we reached the end of the block, we should read next block
                if (localLastUncompressedBlockInfo.lastUncompressedBlockOffset >= uncompressedBlockSize) {
                    blockOffset = 0;
                    startBlockNumber++;
                }
                continue;
            }
            int blockStart = getBlockOffset(startBlockNumber);
            int blockEnd = startBlockNumber + 1 >= blocksCount
                    ? (int) mappedSSTable.byteSize() // the end of the last block is the end of the mappedSSTable
                    : getBlockOffset(startBlockNumber + 1);
            int compressedSize = blockEnd - blockStart;
            int uncompressedSize = startBlockNumber + 1 == blocksCount
                    ? getLastDataUncompressedSize()
                    : uncompressedBlockSize;

            uncompressedBlockBuffer.get().withArray(dest ->
                    decompressOneBlock(
                            blockBuffer,
                            blockStart,
                            dest,
                            compressedSize,
                            uncompressedSize
                    )
            );

            int length = readFromDecompressedBlock(
                    targetDecompressedByteArray,
                    decompressedAndCopiedLength,
                    blockOffset
            );
            decompressedAndCopiedLength += length;
            blockOffset += length;

            localLastUncompressedBlockInfo.lastUncompressedBlockNumber = startBlockNumber;
            localLastUncompressedBlockInfo.lastUncompressedBlockOffset = blockOffset;
            if (localLastUncompressedBlockInfo.lastUncompressedBlockOffset >= uncompressedBlockSize) {
                blockOffset = 0;
            }

            startBlockNumber++;
        }

    }

    private int getLastDataUncompressedSize() {
        // ZSTD specific
        if (decompressor instanceof ZstdDecompressor) return uncompressedBlockSize;

        return mappedCompressionInfo.get(
                ValueLayout.JAVA_INT_UNALIGNED,
                mappedCompressionInfo.byteSize() - Integer.BYTES
        );
    }

    /**
     * Read bytes from decompressed block.
     *
     * @param target       target array
     * @param targetOffset starting position in the target data
     * @param blockOffset  offset in uncompressedBlockBuffer
     * @return the total number of bytes read into the buffer
     */
    private int readFromDecompressedBlock(byte[] target, int targetOffset, int blockOffset) throws IOException {
        int length = Math.min(
                target.length - targetOffset,
                uncompressedBlockSize - blockOffset
        );
        uncompressedBlockBuffer.get().withArray(src ->
                System.arraycopy(
                        src,
                        blockOffset,
                        target,
                        targetOffset,
                        length
                )
        );
        return length;
    }

    private static long byteArrayToLong(byte[] bytes) {
        long value = 0;
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            value <<= 8;
            value |= (bytes[i] & 0xFFL);
        }
        return value;
    }

    private long getEntriesSize() {
        return mappedIndexFile.get(ValueLayout.JAVA_LONG_UNALIGNED, 1);
    }

    @Override
    protected long getEntryOffset(MemorySegment key, SearchOption searchOption) throws IOException {
        // binary search
        int left = 0;
        int right = (int) (getEntriesSize() - 1);
        while (left <= right) {
            int mid = (right + left) >>> 1;
            MemorySegment decompressedKey = readEntry(mid, false).key();

            int keyComparison = MemorySegmentComparator.INSTANCE.compare(
                    decompressedKey,
                    0L,
                    decompressedKey.byteSize(),
                    key
            );

            if (keyComparison < 0) {
                left = mid + 1;
            } else if (keyComparison > 0) {
                right = mid - 1;
            } else {
                return switch (searchOption) {
                    case EQ, GTE -> mid;
                    case LT -> mid - 1;
                };
            }
        }

        return switch (searchOption) {
            case EQ -> -1;
            case GTE -> {
                if (left == getEntriesSize()) {
                    yield -1;
                } else {
                    yield left;
                }
            }
            case LT -> right;
        };
    }

    public final class CompressedSSTableIterator extends LSMPointerIterator {
        private int startEntityNumber;
        private final int endEntityNumber;
        private TimestampedEntry<MemorySegment> current;

        private final ByteArraySegment iteratorCompressedBlockBuffer;
        private final ByteArraySegment iteratorUncompressedBlockBuffer;
        private final LastUncompressedBlockInfo iteratorLastUncompressedBlockInfo;

        private CompressedSSTableIterator(
                int startEntityNumber,
                int endEntityNumber,
                ByteArraySegment iteratorCompressedBlockBuffer,
                ByteArraySegment iteratorUncompressedBlockBuffer,
                LastUncompressedBlockInfo iteratorLastUncompressedBlockInfo
        ) throws IOException {
            this.startEntityNumber = startEntityNumber;
            this.endEntityNumber = endEntityNumber;
            this.iteratorCompressedBlockBuffer = iteratorCompressedBlockBuffer;
            this.iteratorUncompressedBlockBuffer = iteratorUncompressedBlockBuffer;
            this.iteratorLastUncompressedBlockInfo = iteratorLastUncompressedBlockInfo;
            if (startEntityNumber <= endEntityNumber) {
                try {
                    current = ScopedValue
                            .where(blockBuffer, this.iteratorCompressedBlockBuffer)
                            .where(uncompressedBlockBuffer, this.iteratorUncompressedBlockBuffer)
                            .where(lastUncompressedBlockInfo, this.iteratorLastUncompressedBlockInfo)
                            .call(() -> {
                                try {
                                    return readEntry(startEntityNumber, true);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                } catch (Exception e) {
                    throw new IOException(e);
                }
                this.startEntityNumber += 1;
            }
        }

        @Override
        public int getPriority() {
            return index;
        }

        @Override
        protected MemorySegment getPointerKeySrc() {
            return current.key();
        }

        @Override
        protected long getPointerKeySrcOffset() {
            return 0;
        }

        @Override
        protected long getPointerKeySrcSize() {
            return current.key().byteSize();
        }

        @Override
        public boolean isPointerOnTombstone() {
            return current.value() == null;
        }

        @Override
        public void shift() {
            if (startEntityNumber > endEntityNumber) {
                current = null;
                return;
            }
            int finalStartBlockNumber = startEntityNumber;
            try {
                current = ScopedValue
                        .where(blockBuffer, this.iteratorCompressedBlockBuffer)
                        .where(uncompressedBlockBuffer, this.iteratorUncompressedBlockBuffer)
                        .where(lastUncompressedBlockInfo, this.iteratorLastUncompressedBlockInfo)
                        .call(() -> {
                            try {
                                return readEntry(finalStartBlockNumber, true);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
                startEntityNumber += 1;
            } catch (Exception e) {
                throw new UnexpectedIOException(e);
            }
        }

        @Override
        public long getPointerSize() {
            return Utils.getEntrySize(current);
        }

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public TimestampedEntry<MemorySegment> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            TimestampedEntry<MemorySegment> next = current;
            if (startEntityNumber > endEntityNumber) {
                current = null;
            } else {
                shift();
            }
            return next;
        }
    }

    public static boolean isCompressed(MemorySegment mappedCompressionInfo) {
        return mappedCompressionInfo.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    public static Decompressor getDecompressor(MemorySegment mappedCompressionInfo) {
        byte compressorType = mappedCompressionInfo.get(ValueLayout.JAVA_BYTE, 1);
        return switch (compressorType) {
            case 0 -> LZ4Decompressor.INSTANCE;
            case 1 -> ZstdDecompressor.INSTANCE;
            default -> throw new UnknownCompressorTypeException(compressorType);
        };
    }
}
