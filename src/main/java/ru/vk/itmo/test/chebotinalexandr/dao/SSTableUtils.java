package ru.vk.itmo.test.chebotinalexandr.dao;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

public final class SSTableUtils {
    public static final long TOMBSTONE = -1;
    public static final int SS_TABLE_PRIORITY = 2;
    public static final long BLOOM_FILTER_LENGTH_OFFSET = 0;
    public static final long BLOOM_FILTER_HASH_FUNCTIONS_OFFSET = Long.BYTES;
    public static final long ENTRIES_SIZE_OFFSET = 2L * Long.BYTES;

    private SSTableUtils() {

    }

    public static FindResult binarySearch(MemorySegment readSegment, MemorySegment key) {
        long low = -1;
        long high = readSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, ENTRIES_SIZE_OFFSET);

        final long bloomFilterLength = readSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, BLOOM_FILTER_LENGTH_OFFSET);
        final long keyOffset = 3L * Long.BYTES + bloomFilterLength * Long.BYTES;

        while (low < high - 1) {
            long mid = (high - low) / 2 + low;

            long offset = readSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, keyOffset + mid * Long.BYTES);
            long keySize = readSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
            offset += Long.BYTES;

            long mismatch = MemorySegment.mismatch(readSegment, offset, offset + keySize,
                    key, 0, key.byteSize());

            if (mismatch == -1) {
                return new FindResult(true, mid);
            }

            if (mismatch == keySize) {
                low = mid;
                continue;
            }
            if (mismatch == key.byteSize()) {
                high = mid;
                continue;
            }

            int compare = Byte.compare(readSegment.get(ValueLayout.JAVA_BYTE, offset + mismatch),
                    key.get(ValueLayout.JAVA_BYTE, mismatch));

            if (compare > 0) {
                high = mid;
            } else {
                low = mid;
            }
        }

        return new FindResult(false, -(low + 1));
    }

    public static Entry<MemorySegment> get(MemorySegment readSegment, MemorySegment key) {
        final long bloomFilterLength = readSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, BLOOM_FILTER_LENGTH_OFFSET);
        final long keyOffset = 3L * Long.BYTES + Long.BYTES * bloomFilterLength;

        FindResult findResult = binarySearch(readSegment, key);

        if (findResult.found()) {
            return get(readSegment, findResult.index(), keyOffset);
        } else {
            return null;
        }
    }

    private static Entry<MemorySegment> get(MemorySegment sstable, long index, long afterBloomFilterOffset) {
        long offset = afterBloomFilterOffset + index * Byte.SIZE;

        long keyOffset = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
        long keySize = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, keyOffset);
        keyOffset += Long.BYTES;
        MemorySegment key = sstable.asSlice(keyOffset, keySize);
        keyOffset += keySize;
        long valueSize = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, keyOffset);
        keyOffset += Long.BYTES;

        if (valueSize == TOMBSTONE) {
            return new BaseEntry<>(key, null);
        } else {
            return new BaseEntry<>(key, sstable.asSlice(keyOffset, valueSize));
        }
    }

    public static void restoreCompaction(SSTableOffsets offsets, Path basePath, Arena arena) {
        Path pathTmp = basePath.resolve("sstable_" + ".tmp");

        try (FileChannel channel = FileChannel.open(pathTmp, StandardOpenOption.READ)) {
            MemorySegment tmpSstable = channel.map(
                    FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);

            long tag = tmpSstable.get(ValueLayout.JAVA_LONG_UNALIGNED, offsets.getEntriesSizeOffset());
            if (tag == -1) {
                Files.delete(pathTmp);
            } else {
                deleteOldSSTables(basePath);
                Files.move(pathTmp, pathTmp.resolveSibling("sstable_" + 0 + ".dat"),
                        StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (FileNotFoundException | NoSuchFileException e) {
            arena.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void deleteOldSSTables(Path basePath) throws IOException {
        try (Stream<Path> stream = Files.list(basePath)) {
            stream
                    .filter(path -> path.toString().endsWith(".dat"))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    public static long entryByteSize(Entry<MemorySegment> entry) {
        if (entry.value() == null) {
            return entry.key().byteSize();
        }

        return entry.key().byteSize() + entry.value().byteSize();
    }

    public static long sizeOf(final Entry<MemorySegment> entry) {
        if (entry == null) {
            return 0L;
        }

        if (entry.value() == null) {
            return entry.key().byteSize();
        }

        return entry.key().byteSize() + entry.value().byteSize();
    }
}
