package ru.vk.itmo.test.grunskiialexey.dao;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import static ru.vk.itmo.test.grunskiialexey.dao.DiskStorage.NAME_INDEX_FILE;
import static ru.vk.itmo.test.grunskiialexey.dao.DiskStorage.NAME_TMP_INDEX_FILE;

public class Compaction {
    private Compaction() {
    }

    public static Iterator<Entry<MemorySegment>> range(
            Iterator<Entry<MemorySegment>> firstIterator,
            Iterator<Entry<MemorySegment>> secondIterator,
            List<MemorySegment> segmentList,
            MemorySegment from,
            MemorySegment to
    ) {
        List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>(segmentList.size() + 2);
        for (MemorySegment memorySegment : segmentList) {
            iterators.add(iterator(memorySegment, from, to));
        }
        iterators.add(firstIterator);
        iterators.add(secondIterator);

        return new MergeIterator<>(
                iterators,
                Comparator.comparing(Entry::key, MemorySegmentDao::compare),
                entry -> entry.value() == null
        );
    }

    public static MemorySegment compact(
            Arena arena,
            Path storagePath,
            Iterable<Entry<MemorySegment>> iterable
    ) throws IOException {
        final Path indexTmp = storagePath.resolve(NAME_TMP_INDEX_FILE);
        final Path indexFile = storagePath.resolve(NAME_INDEX_FILE);

        List<String> existedFiles = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        final String compactionFileName = DiskStorage.getNewFileName(existedFiles);
        final Path compactionFile = storagePath.resolve(compactionFileName);

        long startValuesOffset = 0;
        long maxOffset = 0;
        for (Entry<MemorySegment> entry : iterable) {
            startValuesOffset++;
            maxOffset += entry.key().byteSize() + entry.value().byteSize();
        }
        startValuesOffset *= 2 * Long.BYTES;
        maxOffset += startValuesOffset;

        final MemorySegment fileSegment;
        try (FileChannel fileChannel = FileChannel.open(
                compactionFile,
                StandardOpenOption.WRITE, StandardOpenOption.READ,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )) {
            fileSegment = fileChannel.map(
                    FileChannel.MapMode.READ_WRITE, 0, maxOffset, arena
            );

            long dataOffset = startValuesOffset;
            int indexOffset = 0;
            for (Entry<MemorySegment> entry : iterable) {
                MemorySegment key = entry.key();
                fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
                MemorySegment.copy(key, 0, fileSegment, dataOffset, key.byteSize());
                dataOffset += key.byteSize();
                indexOffset += Long.BYTES;

                MemorySegment value = entry.value();
                fileSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, dataOffset);
                MemorySegment.copy(value, 0, fileSegment, dataOffset, value.byteSize());
                dataOffset += value.byteSize();
                indexOffset += Long.BYTES;
            }
        }

        Files.move(indexFile, indexTmp, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.write(
                indexFile,
                List.of(compactionFileName),
                StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        );
        Files.delete(indexTmp);

        // Delete old data
        removeExcessFiles(storagePath, compactionFileName);
        return fileSegment;
    }

    private static void removeExcessFiles(Path storagePath, String compactionFile) throws IOException {
        List<Path> excessFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(storagePath, 1)) {
            stream.forEach(path -> {
                String fileName = path.getFileName().toString();
                if (Files.isRegularFile(path)
                        && !fileName.equals(compactionFile)
                        && !fileName.equals(NAME_INDEX_FILE)
                ) {
                    excessFiles.add(path);
                }
            });
        }

        for (Path excessFile : excessFiles) {
            Files.delete(excessFile);
        }
    }

    private static Iterator<Entry<MemorySegment>> iterator(MemorySegment page, MemorySegment from, MemorySegment to) {
        long recordIndexFrom = from == null ? 0 : DiskStorage.normalize(DiskStorage.indexOf(page, from));
        long recordIndexTo = to == null
                ? DiskStorage.recordsCount(page)
                : DiskStorage.normalize(DiskStorage.indexOf(page, to));
        long recordsCount = DiskStorage.recordsCount(page);

        return new Iterator<>() {
            long index = recordIndexFrom;

            @Override
            public boolean hasNext() {
                return index < recordIndexTo;
            }

            @Override
            public Entry<MemorySegment> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                MemorySegment key = DiskStorage.slice(
                        page,
                        DiskStorage.startOfKey(page, index),
                        DiskStorage.endOfKey(page, index)
                );
                long startOfValue = DiskStorage.startOfValue(page, index);
                MemorySegment value =
                        startOfValue < 0
                                ? null
                                : DiskStorage.slice(
                                page,
                                startOfValue,
                                DiskStorage.endOfValue(page, index, recordsCount)
                        );
                index++;
                return new BaseEntry<>(key, value);
            }
        };
    }
}
