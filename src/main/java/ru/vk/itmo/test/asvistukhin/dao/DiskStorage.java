package ru.vk.itmo.test.asvistukhin.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
import java.util.concurrent.CopyOnWriteArrayList;

public class DiskStorage {

    private static final String DATA_FILE_AFTER_COMPACTION = "0";
    private final List<MemorySegment> segmentList;

    public DiskStorage(List<MemorySegment> segmentList) {
        this.segmentList = new CopyOnWriteArrayList<>();
        this.segmentList.addAll(segmentList);
    }

    public void mapSSTableAfterFlush(Path storagePath, String fileName, Arena arena) throws IOException {
        Path file = storagePath.resolve(fileName);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            MemorySegment fileSegment = fileChannel.map(
                FileChannel.MapMode.READ_WRITE,
                0,
                Files.size(file),
                arena
            );
            this.segmentList.add(fileSegment);
        }
    }

    public void mapSSTableAfterCompaction(Path storagePath, Arena arena) throws IOException {
        this.segmentList.clear();
        mapSSTableAfterFlush(storagePath, DATA_FILE_AFTER_COMPACTION, arena);
    }

    public Iterator<TimestampEntry<MemorySegment>> range(
        StorageState storageState,
        MemorySegment from, MemorySegment to
    ) {
        List<Iterator<TimestampEntry<MemorySegment>>> iterators = new ArrayList<>(segmentList.size() + 1);
        for (MemorySegment memorySegment : segmentList) {
            iterators.add(iterator(memorySegment, from, to));
        }

        if (storageState.getFlushingSSTable() != null) {
            iterators.add(storageState.getFlushingSSTable().get(from, to));
        }
        iterators.add(storageState.getActiveSSTable().get(from, to));

        return new MergeIterator<>(iterators, Comparator.comparing(TimestampEntry::key, MemorySegmentUtils::compare)) {
            @Override
            protected boolean shouldSkip(TimestampEntry<MemorySegment> memorySegmentEntry) {
                return memorySegmentEntry.value() == null;
            }
        };
    }

    public Iterator<TimestampEntry<MemorySegment>> rangeFromDisk(MemorySegment from, MemorySegment to) {
        List<Iterator<TimestampEntry<MemorySegment>>> iterators = new ArrayList<>(segmentList.size() + 1);
        iterators.add(Collections.emptyIterator());
        for (MemorySegment memorySegment : segmentList) {
            iterators.add(iterator(memorySegment, from, to));
        }

        return new MergeIterator<>(iterators, Comparator.comparing(Entry::key, MemorySegmentUtils::compare)) {
            @Override
            protected boolean shouldSkip(TimestampEntry<MemorySegment> memorySegmentEntry) {
                return memorySegmentEntry.value() == null;
            }
        };
    }

    public static String save(Path storagePath, Iterable<TimestampEntry<MemorySegment>> iterable) throws IOException {
        final Path indexTmp = storagePath.resolve("index.tmp");
        final Path indexFile = storagePath.resolve("index.idx");
        try {
            Files.createFile(indexFile);
        } catch (FileAlreadyExistsException ignored) {
            // it is ok, actually it is normal state
        }
        List<String> existedFiles = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        String newFileName = String.valueOf(existedFiles.size());
        long dataSize = 0;
        long count = 0;
        for (TimestampEntry<MemorySegment> entry : iterable) {
            dataSize += entry.key().byteSize();
            MemorySegment value = entry.value();
            if (value != null) {
                dataSize += value.byteSize();
            }
            dataSize += Long.BYTES;
            count++;
        }
        long indexSize = count * 2 * Long.BYTES;
        try (
            FileChannel fileChannel = FileChannel.open(
                storagePath.resolve(newFileName),
                StandardOpenOption.WRITE,
                StandardOpenOption.READ,
                StandardOpenOption.CREATE
            );
            Arena writeArena = Arena.ofConfined()
        ) {
            MemorySegment fileSegment = fileChannel.map(
                FileChannel.MapMode.READ_WRITE,
                0,
                indexSize + dataSize,
                writeArena
            );
            long dataOffset = indexSize;
            int indexOffset = 0;
            for (TimestampEntry<MemorySegment> entry : iterable) {
                fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
                dataOffset += entry.key().byteSize();
                indexOffset += Long.BYTES;
                MemorySegment value = entry.value();
                if (value == null) {
                    fileSegment.set(
                        ValueLayout.JAVA_LONG_UNALIGNED,
                        indexOffset,
                        MemorySegmentUtils.tombstone(dataOffset)
                    );
                } else {
                    fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
                    dataOffset += value.byteSize();
                }
                dataOffset += Long.BYTES;
                indexOffset += Long.BYTES;
            }

            dataOffset = indexSize;
            for (TimestampEntry<MemorySegment> entry : iterable) {
                MemorySegment key = entry.key();
                MemorySegment.copy(key, 0, fileSegment, dataOffset, key.byteSize());
                dataOffset += key.byteSize();
                MemorySegment value = entry.value();
                if (value != null) {
                    MemorySegment.copy(value, 0, fileSegment, dataOffset, value.byteSize());
                    dataOffset += value.byteSize();
                }
                fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, dataOffset, entry.timestamp());
                dataOffset += Long.BYTES;
            }
        }

        manageDataFiles(indexFile, indexTmp, newFileName, existedFiles);
        return newFileName;
    }

    public static List<MemorySegment> loadOrRecover(Path storagePath, Arena arena) throws IOException {
        Path indexTmp = storagePath.resolve("index.tmp");
        Path indexFile = storagePath.resolve("index.idx");

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
                MemorySegment fileSegment = fileChannel.map(
                    FileChannel.MapMode.READ_WRITE,
                    0,
                    Files.size(file),
                    arena
                );
                result.add(fileSegment);
            }
        }

        return result;
    }

    public static void deleteObsoleteData(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            files.forEach(file -> {
                if (Files.isRegularFile(file)) {
                    try {
                        Files.delete(file);
                    } catch (IOException ignored) {
                        // it's not okay, check comment in PR please
                    }
                }
            });
        }
    }

    private static void manageDataFiles(
        Path indexFile,
        Path indexTmp,
        String newFileName,
        List<String> existedFiles
    ) throws IOException {
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

    private static Iterator<TimestampEntry<MemorySegment>> iterator(
        MemorySegment page,
        MemorySegment from,
        MemorySegment to
    ) {
        long recordIndexFrom = from == null ? 0 : MemorySegmentUtils.normalize(MemorySegmentUtils.indexOf(page, from));
        long recordIndexTo = to == null
            ? MemorySegmentUtils.recordsCount(page)
            : MemorySegmentUtils.normalize(MemorySegmentUtils.indexOf(page, to));
        long recordsCount = MemorySegmentUtils.recordsCount(page);

        return new Iterator<>() {
            long index = recordIndexFrom;

            @Override
            public boolean hasNext() {
                return index < recordIndexTo;
            }

            @Override
            public TimestampEntry<MemorySegment> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                MemorySegment key = MemorySegmentUtils.slice(
                    page,
                    MemorySegmentUtils.startOfKey(page, index),
                    MemorySegmentUtils.endOfKey(page, index)
                );
                long startOfValue = MemorySegmentUtils.startOfValue(page, index);
                long endOfValue = MemorySegmentUtils.endOfValue(page, index, recordsCount);
                MemorySegment value = startOfValue < 0
                    ? null : MemorySegmentUtils.slice(page, startOfValue, endOfValue);
                long timestamp = page.get(ValueLayout.JAVA_LONG_UNALIGNED, endOfValue);
                index++;
                return new TimestampEntry<>(key, value, timestamp);
            }
        };
    }
}
