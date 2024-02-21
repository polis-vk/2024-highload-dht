package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;

public class SSTable implements DaoFileGet<MemorySegment, Entry<MemorySegment>> {

    public static final Comparator<MemorySegment> COMPARATOR = UtilsMemorySegment::compare;
    public static final Comparator<Entry<MemorySegment>> ENTRY_COMPARATOR = UtilsMemorySegment::compareEntry;
    public static final String INDEX_SUFFIX = "_index";

    private final IndexList indexList;

    private SSTable(final Path indexPath, final Path dataPath, final Arena arena) throws IOException {
        indexList = new IndexList(
                mapSegment(indexPath, arena),
                mapSegment(dataPath, arena)
        );
    }

    public static SSTable create(final Path root, final String name, final Arena arena) throws IOException {
        final Path indexPath = getIndexPath(root, name);
        final Path dataPath = getDataPath(root, name);
        if (Files.notExists(indexPath) || Files.notExists(dataPath)) {
            return null;
        }
        return new SSTable(indexPath, dataPath, arena);
    }

    private static Path getDataPath(final Path root, final String name) {
        return root.resolve(name);
    }

    private static Path getIndexPath(final Path root, final String name) {
        return root.resolve(name + INDEX_SUFFIX);
    }

    private static MemorySegment mapSegment(final Path path, final Arena arena) throws IOException {
        try (FileChannel readerChannel = FileChannel.open(
                path,
                StandardOpenOption.READ)
        ) {
            return readerChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    readerChannel.size(),
                    arena
            );
        }
    }

    private static final class KeyEntry implements Entry<MemorySegment> {

        final MemorySegment key;

        public KeyEntry(final MemorySegment key) {
            this.key = key;
        }

        @Override
        public MemorySegment key() {
            return key;
        }

        @Override
        public MemorySegment value() {
            return null;
        }
    }

    private int binarySearch(final MemorySegment key) {
        return Collections.binarySearch(indexList, new KeyEntry(key), ENTRY_COMPARATOR);
    }

    @Override
    public Entry<MemorySegment> get(final MemorySegment key) throws IOException {
        final int pos = binarySearch(key);
        return pos >= 0 ? indexList.get(pos) : null;
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(final MemorySegment from, final MemorySegment to) throws IOException {
        int startPos = 0;
        if (from != null && (startPos = binarySearch(from)) < 0) {
            startPos = -(startPos + 1);
        }
        int endPos = indexList.size();
        if (to != null && (endPos = binarySearch(to)) < 0) {
            endPos = -(endPos + 1);
        }
        return indexList.subList(startPos, endPos).iterator();
    }

    private static long getTotalMapSize(final SortedMap<MemorySegment, Entry<MemorySegment>> map) {
        long totalSize = 0;
        for (Map.Entry<MemorySegment, Entry<MemorySegment>> entry : map.entrySet()) {
            final MemorySegment value = entry.getValue().value();
            totalSize += entry.getKey().byteSize() + (value == null ? 0 : value.byteSize());
        }
        return totalSize;
    }

    public static void write(
            final SortedMap<MemorySegment, Entry<MemorySegment>> map,
            final Path path,
            final String name
    ) throws IOException {
        long[][] offsets;
        final long mapSize = getTotalMapSize(map);
        try (Arena arena = Arena.ofConfined(); FileChannel writerChannel = FileChannel.open(
                getDataPath(path, name),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)
        ) {
            final MemorySegment memorySegment = writerChannel.map(
                    FileChannel.MapMode.READ_WRITE,
                    0,
                    mapSize,
                    arena
            );
            offsets = new long[map.size()][2];

            int index = 0;
            long totalOffset = 0;
            for (final MemorySegment key : map.keySet()) {
                offsets[index][0] = totalOffset;
                MemorySegment.copy(
                        key,
                        0,
                        memorySegment,
                        totalOffset,
                        key.byteSize()
                );
                totalOffset += key.byteSize();
                index += 1;
            }

            index = 0;
            for (final Entry<MemorySegment> value : map.values()) {
                if (value.value() == null) {
                    offsets[index][1] = -1;
                } else {
                    offsets[index][1] = totalOffset;
                    MemorySegment.copy(
                            value.value(),
                            0,
                            memorySegment,
                            totalOffset,
                            value.value().byteSize()
                    );
                    totalOffset += value.value().byteSize();
                }
                index += 1;
            }
        }

        try (Arena arena = Arena.ofConfined(); FileChannel writerChannel = FileChannel.open(
                getIndexPath(path, name),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)
        ) {
            final MemorySegment memorySegment = writerChannel.map(
                    FileChannel.MapMode.READ_WRITE,
                    0,
                    IndexList.getFileSize(map),
                    arena
            );
            IndexList.write(memorySegment, offsets, mapSize);
        }
    }

}
