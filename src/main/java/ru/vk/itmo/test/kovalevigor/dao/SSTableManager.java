package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.dao.entry.DaoEntry;
import ru.vk.itmo.test.kovalevigor.dao.iterators.MemEntryPriorityIterator;
import ru.vk.itmo.test.kovalevigor.dao.iterators.MergeEntryIterator;
import ru.vk.itmo.test.kovalevigor.dao.iterators.PriorityShiftedIterator;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static ru.vk.itmo.test.kovalevigor.dao.SSTableUtils.getDataPath;
import static ru.vk.itmo.test.kovalevigor.dao.SSTableUtils.getIndexPath;

public abstract class SSTableManager<T extends DaoEntry<MemorySegment>> implements
        DaoFileGet<MemorySegment, T>,
        AutoCloseable {

    public static final String SSTABLE_NAME = "sstable";

    private final Path root;
    private final Arena arena;
    private final List<SSTable> ssTables;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Set<SSTable> deadSSTables;
    private int totalSize;

    protected SSTableManager(final Path root) throws IOException {
        this.root = root;
        this.arena = Arena.ofShared();
        this.ssTables = readTables();
        this.deadSSTables = new HashSet<>();
        this.totalSize = ssTables.size();
    }

    private List<SSTable> readTables() throws IOException {
        final List<SSTable> tables = new ArrayList<>();
        SSTable table;
        while ((table = readTable(getFormattedSSTableName(tables.size()))) != null) {
            tables.add(table);
        }
        return new CopyOnWriteArrayList<>(tables.reversed());
    }

    private SSTable readTable(final String name) throws IOException {
        return SSTable.create(root, name, arena);
    }

    private static String getFormattedSSTableName(final int size) {
        return SSTABLE_NAME + size;
    }

    private synchronized String getNextSSTableName() {
        return getFormattedSSTableName(totalSize++);
    }

    public String write(SortedMap<MemorySegment, T> map) throws IOException {
        if (map.isEmpty()) {
            return null;
        }

        final String name = getNextSSTableName();
        write(map, root, name);
        return name;
    }

    public static SizeInfo getMapSize(final SortedMap<MemorySegment, ? extends DaoEntry<MemorySegment>> map) {
        long keysSize = 0;
        long valuesSize = 0;
        for (Map.Entry<MemorySegment, ? extends DaoEntry<MemorySegment>> entry : map.entrySet()) {
            final DaoEntry<MemorySegment> value = entry.getValue();

            keysSize += entry.getKey().byteSize();
            valuesSize += value.valueSize();
        }
        return new SizeInfo(map.size(), keysSize, valuesSize);
    }

    public void write(
            final SortedMap<MemorySegment, T> map,
            final Path path,
            final String name
    ) throws IOException {
        try (Arena wArena = Arena.ofConfined(); SStorageDumper<T> dumper = getDumper(
                getMapSize(map),
                getDataPath(path, name),
                getIndexPath(path, name),
                wArena
        )) {
            for (final T entry: map.values()) {
                dumper.writeEntry(entry);
            }
        }
    }

    public void addSSTable(final String name) throws IOException {
        lock.writeLock().lock();
        try {
            ssTables.addFirst(SSTable.create(root, name, arena));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Iterator<T> get(
            Collection<SSTable> ssTables,
            final MemorySegment from,
            final MemorySegment to
    ) throws IOException {
        List<PriorityShiftedIterator<T>> iterators = new ArrayList<>();
        int i = 0;
        for (final SSTable ssTable : ssTables) {
            iterators.add(new MemEntryPriorityIterator<>(i, keyValueEntryTo(ssTable.get(from, to))));
            i++;
        }
        return new MergeEntryIterator<>(iterators);
    }

    @Override
    public Iterator<T> get(final MemorySegment from, final MemorySegment to) throws IOException {
        return get(ssTables, from, to);
    }

    @Override
    public T get(final MemorySegment key) throws IOException {
        T value = null;
        for (final SSTable ssTable : ssTables) {
            value = keyValueEntryTo(ssTable.get(key));
            System.out.println(ssTable.get(key));
            if (value != null) {
                if (value.value() == null) {
                    value = null;
                }
                break;
            }
        }
        return value;
    }

    // TODO: Теперь нужен еще и mergeEntries для значений
    private static SizeInfo getTotalInfoSize(final Collection<SSTable> ssTables) {
        final SizeInfo result = new SizeInfo();
        for (SSTable ssTable: ssTables) {
            result.add(ssTable.getSizeInfo());
        }
        return result;
    }

    public synchronized void compact() throws IOException {
        final List<SSTable> compactTables = new ArrayList<>(ssTables);
        if (compactTables.size() <= 1) {
            return;
        }

        Path tableTmpPath = null;
        Path indexTmpPath = null;
        final SizeInfo sizes = getTotalInfoSize(compactTables);
        try {
            tableTmpPath = Files.createTempFile(null, null);
            indexTmpPath = Files.createTempFile(null, null);
            final SizeInfo realSize = new SizeInfo();
            try (Arena tmpArena = Arena.ofConfined(); SStorageDumper<T> dumper = getDumper(
                    sizes,
                    tableTmpPath,
                    indexTmpPath,
                    tmpArena
            )) {
                final Iterator<T> iterator = new MergeEntryIterator<>(List.of(
                        new MemEntryPriorityIterator<>(0, get(compactTables, null, null))
                ));
                while (iterator.hasNext()) {
                    final T entry = iterator.next();
                    if (entry.value() != null) {
                        dumper.writeEntry(entry);
                        realSize.size += 1;
                        realSize.keysSize += entry.key().byteSize();
                        realSize.valuesSize += entry.value().byteSize();
                    }
                }
                dumper.setKeysSize(realSize.keysSize);
                dumper.setValuesSize(realSize.valuesSize);
            }

            try (FileChannel dataChannel = FileChannel.open(tableTmpPath, StandardOpenOption.WRITE);
                 FileChannel indexChannel = FileChannel.open(indexTmpPath, StandardOpenOption.WRITE)
            ) {
                dataChannel.truncate(SStorageDumper.getSize(realSize.keysSize, realSize.valuesSize));
                indexChannel.truncate(SStorageDumper.getIndexSize(realSize.size));
            }

            final String sstableName = getNextSSTableName();

            final Path dataPath = getDataPath(root, sstableName);
            final Path indexPath = getIndexPath(root, sstableName);

            Files.copy(tableTmpPath, dataPath);
            try {
                Files.copy(indexTmpPath, indexPath);
            } catch (IOException e) {
                Files.deleteIfExists(dataPath);
                throw e;
            }

            lock.writeLock().lock();
            try {
                ssTables.add(SSTable.create(root, sstableName, arena));
                ssTables.removeAll(compactTables);
                deadSSTables.addAll(compactTables);
            } finally {
                lock.writeLock().unlock();
            }
        } finally {
            if (tableTmpPath != null) {
                Files.deleteIfExists(tableTmpPath);
            }
            if (indexTmpPath != null) {
                Files.deleteIfExists(indexTmpPath);
            }
        }
    }

    @Override
    public void close() throws IOException {

        if (!arena.scope().isAlive()) {
            return;
        }
        arena.close();

        if (!deadSSTables.isEmpty()) {
            for (final SSTable ssTable : deadSSTables) {
                ssTable.delete();
            }

            int size = 0;
            for (final SSTable ssTable : ssTables.reversed()) {
                ssTable.move(root, getFormattedSSTableName(size));
                size += 1;
            }
        }
        deadSSTables.clear();

        ssTables.clear();
    }

    public abstract T mergeEntries(T oldEntry, T newEntry);

    public abstract boolean shouldBeNull(T entry);

    protected abstract SStorageDumper<T> getDumper(
            SizeInfo sizeInfo,
            Path storagePath,
            Path indexPath,
            Arena arena
    ) throws IOException;

    protected abstract T keyValueEntryTo(Entry<MemorySegment> entry);

    protected abstract Iterator<T> keyValueEntryTo(Iterator<Entry<MemorySegment>> entry);
}
