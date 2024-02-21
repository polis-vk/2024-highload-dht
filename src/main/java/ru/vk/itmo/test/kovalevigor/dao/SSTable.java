package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;

public class SSTable implements DaoFileGet<MemorySegment, Entry<MemorySegment>> {

    public static final Comparator<MemorySegment> COMPARATOR = UtilsMemorySegment::compare;
    public static final Comparator<Entry<MemorySegment>> ENTRY_COMPARATOR = UtilsMemorySegment::compareEntry;

    private Path indexPath;
    private Path dataPath;
    private final IndexList indexList;

    private SSTable(final Path indexPath, final Path dataPath, final Arena arena) throws IOException {
        this.indexPath = indexPath;
        this.dataPath = dataPath;
        this.indexList = new IndexList(
                UtilsMemorySegment.mapReadSegment(indexPath, arena),
                UtilsMemorySegment.mapReadSegment(dataPath, arena)
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

    public static Path getDataPath(final Path root, final String name) {
        return root.resolve(name);
    }

    public static Path getIndexPath(final Path root, final String name) {
        return root.resolve(name + "_index");
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

    public static SizeInfo getMapSize(final SortedMap<MemorySegment, Entry<MemorySegment>> map) {
        long keysSize = 0;
        long valuesSize = 0;
        for (Map.Entry<MemorySegment, Entry<MemorySegment>> entry : map.entrySet()) {
            final MemorySegment value = entry.getValue().value();

            keysSize += entry.getKey().byteSize();
            valuesSize += value == null ? 0 : value.byteSize();
        }
        return new SizeInfo(map.size(), keysSize, valuesSize);
    }

    public static void write(
            final SortedMap<MemorySegment, Entry<MemorySegment>> map,
            final Path path,
            final String name
    ) throws IOException {
        try (Arena arena = Arena.ofConfined(); SStorageDumper dumper = new SStorageDumper(
                getMapSize(map),
                getDataPath(path, name),
                getIndexPath(path, name),
                arena
            )) {
            for (final Entry<MemorySegment> entry: map.values()) {
                dumper.writeEntry(entry);
            }
        }
    }

    public void move(
            final Path path,
            final String name
    ) throws IOException {
        final Path targetDataPath = getDataPath(path, name);
        final Path targetIndexPath = getIndexPath(path, name);
        Files.move(dataPath, targetDataPath);
        try {
            Files.move(indexPath, targetIndexPath);
        } catch (IOException e) {
            Files.move(targetDataPath, dataPath);
            throw e;
        }
        dataPath = targetDataPath;
        indexPath = targetIndexPath;
    }

    public SizeInfo getSizeInfo() {
        return new SizeInfo(indexList.size(), indexList.keysSize(), indexList.valuesSize());
    }

    public void delete() throws IOException {
        try {
            Files.delete(dataPath);
        } finally {
            Files.delete(indexPath);
        }
    }

}
