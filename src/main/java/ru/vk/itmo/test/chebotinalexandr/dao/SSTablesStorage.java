package ru.vk.itmo.test.chebotinalexandr.dao;

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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.binarySearch;
import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.deleteOldSSTables;
import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.entryByteSize;
import static ru.vk.itmo.test.chebotinalexandr.dao.SSTableUtils.restoreCompaction;

public class SSTablesStorage {
    private static final long TOMBSTONE = -1;
    private static final String SSTABLE_NAME = "sstable_";
    private static final String SSTABLE_EXTENSION = ".dat";
    private static final long OLDEST_SS_TABLE_INDEX = 0;
    private static final long COMPACTION_NOT_FINISHED_TAG = -1;
    private final Path basePath;
    public static final int HASH_FUNCTIONS_NUM = 2;
    private static final SSTableOffsets offsetsConfig =
            new SSTableOffsets(Long.BYTES, 0, 2L * Long.BYTES);

    public SSTablesStorage(Path basePath) {
        this.basePath = basePath;
    }

    public static List<MemorySegment> loadOrRecover(Path basePath, Arena arena) {
        List<MemorySegment> sstables = new ArrayList<>();

        if (compactionTmpFileExists(basePath)) {
            restoreCompaction(offsetsConfig, basePath, arena);
        }

        try (Stream<Path> stream = Files.list(basePath)) {
            stream
                    .filter(path -> path.toString().endsWith(SSTABLE_EXTENSION))
                    .map(path -> new AbstractMap.SimpleEntry<>(path, parsePriority(path)))
                    .sorted(priorityComparator().reversed())
                    .forEach(entry -> {
                        try (FileChannel channel = FileChannel.open(entry.getKey(), StandardOpenOption.READ)) {
                            MemorySegment readSegment = channel.map(
                                    FileChannel.MapMode.READ_ONLY,
                                    0,
                                    channel.size(),
                                    arena);

                            sstables.add(readSegment);
                        } catch (FileNotFoundException | NoSuchFileException e) {
                            arena.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (NoSuchFileException e) {
            arena.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return sstables;
    }

    private static boolean compactionTmpFileExists(Path basePath) {
        Path pathTmp = basePath.resolve(SSTABLE_NAME + ".tmp");
        return Files.exists(pathTmp);
    }

    private static int parsePriority(Path path) {
        String fileName = path.getFileName().toString();
        return Integer.parseInt(fileName.substring(fileName.indexOf('_') + 1, fileName.indexOf('.')));
    }

    private static Comparator<Map.Entry<Path, Integer>> priorityComparator() {
        return Comparator.comparingInt(Map.Entry::getValue);
    }

    public static FindResult find(MemorySegment readSegment, MemorySegment key) {
        return binarySearch(readSegment, key);
    }

    public static Iterator<Entry<MemorySegment>> iteratorsAll(
            List<MemorySegment> segments,
            MemorySegment from,
            MemorySegment to
    ) {
        List<PeekingIterator<Entry<MemorySegment>>> result = new ArrayList<>();

        int priority = 1;
        for (MemorySegment sstable : segments) {
            result.add(new PeekingIteratorImpl<>(iteratorOf(sstable, from, to), priority));
            priority++;
        }
        return MergeIterator.merge(result, NotOnlyInMemoryDao::entryComparator);
    }

    public static Iterator<Entry<MemorySegment>> iteratorOf(
            MemorySegment sstable,
            MemorySegment from,
            MemorySegment to
    ) {
        long keyIndexFrom;
        long keyIndexTo;

        if (from == null && to == null) {
            keyIndexFrom = 0;
            keyIndexTo = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, offsetsConfig.getEntriesSizeOffset());
        } else if (from == null) {
            keyIndexFrom = 0;
            keyIndexTo = find(sstable, to).index();
        } else if (to == null) {
            keyIndexFrom = find(sstable, from).index();
            keyIndexTo = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED, offsetsConfig.getEntriesSizeOffset());
        } else {
            keyIndexFrom = find(sstable, from).index();
            keyIndexTo = find(sstable, to).index();
        }

        final long bloomFilterLength = sstable.get(ValueLayout.JAVA_LONG_UNALIGNED,
                offsetsConfig.getBloomFilterLengthOffset());
        final long keyOffset = 3L * Long.BYTES + bloomFilterLength * Long.BYTES;

        if (keyIndexFrom < 0) {
            keyIndexFrom = Math.abs(keyIndexFrom);
        }
        if (keyIndexTo < 0) {
            keyIndexTo = Math.abs(keyIndexTo);
        }

        return new SSTableIterator(sstable, keyIndexFrom, keyIndexTo, keyOffset);
    }

    /**
     * Writes SSTable in next format:
     * SSTable header contains:
     * ┌─────────────────────┬─────────────────────────┬─────────────────────┐
     * │          8          │            8            │          8          │
     * │─────────────────────│─────────────────────────│─────────────────────│
     * │   BF array length   │  Hash functions count   │    Entries count    │
     * └─────────────────────┴─────────────────────────┴─────────────────────┘
     * SStable bloom filter:
     * ┌────────────────────────────┐
     * │ 8 x BloomFilter array size │
     * │────────────────────────────│
     * │           Hash_i           │
     * └────────────────────────────┘
     * where i = 1, ... , bloom filter array size
     * SStable data format:
     * ┌─────────────────────┬──────────┬──────────┬────────────┬────────────┐
     * │  8 x Entries count  │    8     │ Key size │     8      │ Value size │
     * │─────────────────────│──────────│──────────│────────────│────────────│
     * │     Key_j offset    │ Key size │    Key   │ Value size │ Value      │
     * └─────────────────────┴──────────┴──────────┴────────────┴────────────┘
     * where j = 1, ... , entries count.
     */
    public MemorySegment write(Collection<Entry<MemorySegment>> dataToFlush, double bloomFilterFPP) throws IOException {
        long size = 0;

        for (Entry<MemorySegment> entry : dataToFlush) {
            size += entryByteSize(entry);
        }

        long bloomFilterLength = BloomFilter.bloomFilterLength(dataToFlush.size(), bloomFilterFPP);

        size += 2L * Long.BYTES * dataToFlush.size();
        size += 3L * Long.BYTES + (long) Long.BYTES * dataToFlush.size(); //for metadata (header + key offsets)
        size += Long.BYTES * bloomFilterLength; //for bloom filter

        MemorySegment memorySegment;
        Arena arenaForSave = Arena.ofShared();
        memorySegment = writeMappedSegment(size, arenaForSave);

        //Writing sstable header
        long headerOffset = 0;

        memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED,
                offsetsConfig.getBloomFilterLengthOffset(), bloomFilterLength);
        headerOffset += Long.BYTES;
        memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED,
                offsetsConfig.getBloomFilterHashFunctionsOffset(), HASH_FUNCTIONS_NUM);
        headerOffset += Long.BYTES;
        memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED,
                offsetsConfig.getEntriesSizeOffset(), dataToFlush.size());
        headerOffset += Long.BYTES;
        //---------

        //Writing bloom filter + memory entries
        long bloomFilterOffset = headerOffset;
        final long keyOffset = bloomFilterOffset + bloomFilterLength * Long.BYTES;
        long offset = keyOffset + (long) Long.BYTES * dataToFlush.size();

        long i = 0;
        for (Entry<MemorySegment> entry : dataToFlush) {
            BloomFilter.addToSstable(entry.key(), memorySegment, HASH_FUNCTIONS_NUM,
                    bloomFilterLength * Long.SIZE);
            memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, keyOffset + i * Long.BYTES, offset);
            offset = writeEntry(entry, memorySegment, offset);
            i++;
        }
        //---------

        return memorySegment;
    }

    private long writeEntry(Entry<MemorySegment> entry, MemorySegment dst, long offset) {
        long newOffset = writeSegment(entry.key(), dst, offset);

        if (entry.value() == null) {
            dst.set(ValueLayout.JAVA_LONG_UNALIGNED, newOffset, TOMBSTONE);
            newOffset += Long.BYTES;
        } else {
            newOffset = writeSegment(entry.value(), dst, newOffset);
        }

        return newOffset;
    }

    private long writeSegment(MemorySegment src, MemorySegment dst, long offset) {
        long size = src.byteSize();
        long newOffset = offset;

        dst.set(ValueLayout.JAVA_LONG_UNALIGNED, newOffset, size);
        newOffset += Long.BYTES;
        MemorySegment.copy(src, 0, dst, newOffset, size);
        newOffset += size;

        return newOffset;
    }

    private static List<Path> getPaths(Path basePath) throws IOException {
        try (Stream<Path> s = Files.list(basePath)) {
            return s.filter(path -> path.toString().endsWith(SSTABLE_EXTENSION)).collect(Collectors.toList());
        }
    }

    public MemorySegment compact(Iterator<Entry<MemorySegment>> iterator,
                                 long sizeForCompaction, long entryCount, long bfLength) throws IOException {
        Path path = basePath.resolve(SSTABLE_NAME + ".tmp");

        MemorySegment memorySegment;
        try (Arena arenaForCompact = Arena.ofShared()) {
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE)) {
                memorySegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, sizeForCompaction,
                        arenaForCompact);
            }

            //Writing sstable header
            long headerOffset = 0;

            memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, offsetsConfig.getBloomFilterLengthOffset(), bfLength);
            headerOffset += Long.BYTES;
            memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED,
                    offsetsConfig.getBloomFilterHashFunctionsOffset(), HASH_FUNCTIONS_NUM);
            headerOffset += Long.BYTES;
            memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED,
                    offsetsConfig.getEntriesSizeOffset(), COMPACTION_NOT_FINISHED_TAG);
            headerOffset += Long.BYTES;
            //---------

            //Writing new Bloom filter and entries
            long bloomFilterOffset = headerOffset;
            final long keyOffset = bloomFilterOffset + bfLength * Long.BYTES;
            long offset = keyOffset + Long.BYTES * entryCount;

            long index = 0;
            while (iterator.hasNext()) {
                Entry<MemorySegment> entry = iterator.next();

                BloomFilter.addToSstable(entry.key(), memorySegment, HASH_FUNCTIONS_NUM, bfLength * Long.SIZE);
                memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, keyOffset + index * Long.BYTES, offset);
                offset = writeEntry(entry, memorySegment, offset);
                index++;
            }

            memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, offsetsConfig.getEntriesSizeOffset(), entryCount);

            deleteOldSSTables(basePath);
            Files.move(path, path.resolveSibling(SSTABLE_NAME + OLDEST_SS_TABLE_INDEX + SSTABLE_EXTENSION),
                    StandardCopyOption.ATOMIC_MOVE);
        }

        return memorySegment;
    }

    private MemorySegment writeMappedSegment(long size, Arena arena) throws IOException {
        int count = getPaths(basePath).size() + 1;
        Path path = basePath.resolve(SSTABLE_NAME + count + SSTABLE_EXTENSION);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE)) {

            return channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
        }
    }
}
