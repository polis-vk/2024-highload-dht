package ru.vk.itmo.test.solnyshkoksenia.dao.storage;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.EntryExtended;
import ru.vk.itmo.test.solnyshkoksenia.dao.MemorySegmentComparator;
import ru.vk.itmo.test.solnyshkoksenia.dao.MergeIterator;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class DiskStorage {
    private static final Comparator<MemorySegment> comparator = new MemorySegmentComparator();
    private static final String INDEX_FILE_NAME = "index.idx";
    private static final StorageUtils utils = new StorageUtils();
    private final Path storagePath;
    private final List<MemorySegment> segmentList;

    public DiskStorage(List<MemorySegment> segmentList, Path storagePath) {
        this.segmentList = segmentList;
        this.storagePath = storagePath;
    }

    public Iterator<EntryExtended<MemorySegment>> range(
            Iterator<EntryExtended<MemorySegment>> firstIterator,
            MemorySegment from,
            MemorySegment to) {
        List<Iterator<EntryExtended<MemorySegment>>> iterators = new ArrayList<>(segmentList.size() + 1);
        for (MemorySegment memorySegment : segmentList) {
            iterators.add(iterator(memorySegment, from, to));
        }
        iterators.add(firstIterator);

        return new MergeIterator<>(iterators, (e1, e2) -> comparator.compare(e1.key(), e2.key())) {
            @Override
            protected boolean skip(EntryExtended<MemorySegment> memorySegmentEntry) {
                if (memorySegmentEntry.expiration() != null) {
                    return memorySegmentEntry.value() == null
                            || !utils.checkTTL(memorySegmentEntry.expiration(), System.currentTimeMillis());
                }
                return memorySegmentEntry.value() == null;
            }
        };
    }

    public void save(Iterable<EntryExtended<MemorySegment>> iterable)
            throws IOException {
        final Path indexTmp = storagePath.resolve("index.tmp");
        final Path indexFile = storagePath.resolve(INDEX_FILE_NAME);

        try {
            Files.createFile(indexFile);
        } catch (FileAlreadyExistsException ignored) {
            // it is ok, actually it is normal state
        }
        List<String> existedFiles = Files.readAllLines(indexFile, StandardCharsets.UTF_8);

        String newFileName = String.valueOf(existedFiles.size());

        final long currentTime = System.currentTimeMillis();

        Entry<Long> sizes = utils.countSizes(iterable, currentTime);

        long dataSize = sizes.key();
        long count = sizes.value();

        if (count == 0) {
            return;
        }
        long indexSize = count * 4 * Long.BYTES;

        try (
                FileChannel fileChannel = FileChannel.open(
                        storagePath.resolve(newFileName),
                        StandardOpenOption.WRITE,
                        StandardOpenOption.READ,
                        StandardOpenOption.CREATE
                );
                Arena writeArena = Arena.ofConfined()
        ) {
            MemorySegment fileSegment = utils.mapFile(fileChannel, indexSize + dataSize, writeArena);

            // index:
            // |key0_Start|value0_Start|expiration0_Start|key1_Start|value1_Start|expiration1_Start|key2_Start|...
            // key0_Start = data start = end of index

            // data:
            // |key0|value0|expiration0|key1|value1|expiration1|...
            Entry<Long> offsets = new BaseEntry<>(indexSize, 0L);
            for (EntryExtended<MemorySegment> entry : iterable) {
                MemorySegment expiration = entry.expiration();
                if (expiration == null || utils.checkTTL(expiration, currentTime)) {
                    offsets = utils.putEntry(fileSegment, offsets, entry);
                }
            }
        }

        Files.move(indexFile, indexTmp, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        List<String> list = new ArrayList<>(existedFiles.size() + 1);
        list.addAll(existedFiles);
        list.add(newFileName);
        Files.write(
                indexFile,
                list,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        Files.delete(indexTmp);
    }

    public void compact() throws IOException {
        final Path tmpFile = storagePath.resolve("tmp");
        final Path indexFile = storagePath.resolve(INDEX_FILE_NAME);

        try {
            Files.createFile(indexFile);
        } catch (FileAlreadyExistsException ignored) {
            // it is ok, actually it is normal state
        }

        List<String> existedFiles = Files.readAllLines(indexFile, StandardCharsets.UTF_8);

        if (existedFiles.isEmpty()) {
            return; // nothing to compact
        }

        Iterator<EntryExtended<MemorySegment>> iterator = range(Collections.emptyIterator(), null, null);
        Iterator<EntryExtended<MemorySegment>> iterator1 = range(Collections.emptyIterator(), null, null);

        long dataSize = 0;
        long indexSize = 0;
        while (iterator.hasNext()) {
            indexSize += Long.BYTES * 4;
            EntryExtended<MemorySegment> entry = iterator.next();
            dataSize += entry.key().byteSize();
            MemorySegment value = entry.value();
            if (value != null) {
                dataSize += value.byteSize();
            }
            dataSize += entry.timestamp().byteSize();
            MemorySegment expiration = entry.expiration();
            if (expiration != null) {
                dataSize += expiration.byteSize();
            }
        }

        try (
                FileChannel fileChannel = FileChannel.open(
                        tmpFile,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.READ,
                        StandardOpenOption.CREATE
                );
                Arena writeArena = Arena.ofConfined()
        ) {
            MemorySegment fileSegment = utils.mapFile(fileChannel, indexSize + dataSize, writeArena);

            Entry<Long> offsets = new BaseEntry<>(indexSize, 0L);
            while (iterator1.hasNext()) {
                offsets = utils.putEntry(fileSegment, offsets, iterator1.next());
            }
        }

        for (String file : existedFiles) {
            Files.delete(storagePath.resolve(file));
        }

        final Path indexTmp = storagePath.resolve("indexTmp");
        Files.deleteIfExists(indexTmp);

        boolean noData = Files.size(tmpFile) == 0;

        Files.write(
                indexTmp,
                noData ? Collections.emptyList() : List.of("0"),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        Files.move(indexTmp, indexFile, StandardCopyOption.ATOMIC_MOVE);
        if (noData) {
            Files.delete(tmpFile);
        } else {
            Files.move(tmpFile, storagePath.resolve("0"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static List<MemorySegment> loadOrRecover(Path storagePath, Arena arena) throws IOException {
        Path indexTmp = storagePath.resolve("index.tmp");
        Path indexFile = storagePath.resolve(INDEX_FILE_NAME);

        if (Files.exists(indexTmp)) {
            Files.move(indexTmp, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            try {
                Files.createFile(indexFile);
            } catch (FileAlreadyExistsException ignored) {
                // it is ok, actually it is normal state
            }
        }

        List<String> existedFiles = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        List<MemorySegment> result = new ArrayList<>(existedFiles.size());
        for (String fileName : existedFiles) {
            Path file = storagePath.resolve(fileName);
            try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                MemorySegment fileSegment = utils.mapFile(fileChannel, Files.size(file), arena);
                result.add(fileSegment);
            }
        }

        return result;
    }

    private static Iterator<EntryExtended<MemorySegment>> iterator(MemorySegment page,
                                                                   MemorySegment from, MemorySegment to) {
        long recordIndexFrom = from == null ? 0 : utils.normalize(utils.indexOf(page, from));
        long recordIndexTo = to == null ? utils.recordsCount(page) : utils.normalize(utils.indexOf(page, to));
        long recordsCount = utils.recordsCount(page);

        return new Iterator<>() {
            long index = recordIndexFrom;

            @Override
            public boolean hasNext() {
                return index < recordIndexTo;
            }

            @Override
            public EntryExtended<MemorySegment> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                MemorySegment key = utils.slice(page, utils.startOfKey(page, index), utils.endOfKey(page, index));

                long startOfValue = utils.startOfValue(page, index);
                MemorySegment value =
                        startOfValue < 0
                                ? null
                                : utils.slice(page, startOfValue, utils.endOfValue(page, index));

                MemorySegment timestamp = utils.slice(page, utils.startOfTimestamp(page, index),
                        utils.endOfTimestamp(page, index));

                long startOfExp = utils.startOfExpiration(page, index);
                MemorySegment expiration =
                        startOfExp < 0
                                ? null
                                : utils.slice(page, startOfExp, utils.endOfExpiration(page, index, recordsCount));

                index++;
                return new EntryExtended<>(key, value, timestamp, expiration);
            }
        };
    }
}
