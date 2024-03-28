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

import static ru.vk.itmo.test.kovalevigor.dao.SSTableUtils.getDataPath;
import static ru.vk.itmo.test.kovalevigor.dao.SSTableUtils.getIndexPath;

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
