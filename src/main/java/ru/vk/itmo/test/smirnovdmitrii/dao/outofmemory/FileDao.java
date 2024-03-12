package ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.OpenedSSTable;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTable;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTableIterator;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTableStorage;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTableStorageImpl;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTableUtil;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.State;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.StateService;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.MemorySegmentComparator;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.iterators.MergeIterator;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.iterators.WrappedIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileDao implements OutMemoryDao<MemorySegment, Entry<MemorySegment>> {
    private static final String INDEX_FILE_NAME = "index.idx";
    private final MemorySegmentComparator comparator = new MemorySegmentComparator();
    private final SSTableStorage storage;
    private final AtomicBoolean isCompacting = new AtomicBoolean(false);
    private final ExecutorService compactor = Executors.newSingleThreadExecutor();
    private final Path basePath;

    public FileDao(
            final Path basePath,
            final StateService stateService
    ) {
        this.basePath = basePath;
        try {
            Files.createDirectories(basePath);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final Path indexFilePath = basePath.resolve(INDEX_FILE_NAME);
        try {
            if (!Files.exists(indexFilePath)) {
                Files.createFile(indexFilePath);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            this.storage = new SSTableStorageImpl(basePath, stateService, INDEX_FILE_NAME);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Entry<MemorySegment> get(
            final State state,
            final MemorySegment key
    ) {
        Objects.requireNonNull(key);
        for (final SSTable ssTable : state.ssTables()) {
            try (OpenedSSTable openedSSTable = ssTable.open()) {
                if (openedSSTable != null) {
                    final long offset = openedSSTable.binarySearch(key, comparator);
                    if (offset >= 0) {
                        return openedSSTable.readBlock(offset);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void flush(final Iterable<Entry<MemorySegment>> entries) throws IOException {
        storage.add(save(entries));
    }

    /**
     * Saves entries with one byte block per element.
     * One block is:
     * [bytes] key [bytes] value.
     * One meta block is :
     * [JAVA_LONG_UNALIGNED] key_offset [JAVA_LONG_UNALIGNED] value_offset
     * SSTable structure:
     * meta block 1
     * meta block 2
     * ...
     * meta block n
     * block 1
     * block 2
     * ...
     * block n
     *
     * @return File name, where entries were saved.
     */
    public String save(
            final Iterable<Entry<MemorySegment>> entries
    ) throws IOException {
        Objects.requireNonNull(entries, "entries must be not null");
        long appendSize = 0;
        long count = 0;
        for (final Entry<MemorySegment> entry : entries) {
            count++;
            appendSize += entry.key().byteSize();
            final MemorySegment value = entry.value();
            if (value != null) {
                appendSize += value.byteSize();
            }
        }
        if (count == 0) {
            return null;
        }
        final long offsetsPartSize = count * Long.BYTES * 2;
        appendSize += offsetsPartSize;
        final Path filePath = newSsTablePath();
        final Path tmpFilePath = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try (Arena savingArena = Arena.ofConfined()) {
            final MemorySegment mappedSsTable;
            try (FileChannel channel = FileChannel.open(
                    tmpFilePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE)
            ) {
                mappedSsTable = channel.map(FileChannel.MapMode.READ_WRITE, 0, appendSize, savingArena);
            }
            long indexOffset = 0;
            long blockOffset = offsetsPartSize;
            for (final Entry<MemorySegment> entry : entries) {
                mappedSsTable.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, blockOffset);
                indexOffset += Long.BYTES;
                final MemorySegment key = entry.key();
                final long keySize = key.byteSize();
                MemorySegment.copy(key, 0, mappedSsTable, blockOffset, keySize);
                final MemorySegment value = entry.value();
                blockOffset += keySize;
                if (value == null) {
                    mappedSsTable.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, SSTableUtil.tombstone(blockOffset));
                } else {
                    mappedSsTable.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, blockOffset);
                    final long valueSize = value.byteSize();
                    MemorySegment.copy(value, 0, mappedSsTable, blockOffset, valueSize);
                    blockOffset += valueSize;
                }
                indexOffset += Long.BYTES;
            }
        }
        Files.move(
                tmpFilePath,
                filePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
        return filePath.getFileName().toString();
    }

    private Path newSsTablePath() {
        return basePath.resolve(UUID.randomUUID().toString());
    }

    private List<Iterator<Entry<MemorySegment>>> get(
            final MemorySegment from,
            final MemorySegment to,
            final Iterable<SSTable> iterable
    ) {
        final List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>();
        for (final SSTable ssTable : iterable) {
            iterators.add(new SSTableIterator(
                    ssTable, from, to, storage, comparator
            ));
        }
        return iterators;
    }

    @Override
    public List<Iterator<Entry<MemorySegment>>> get(
            final State state,
            final MemorySegment from,
            final MemorySegment to
    ) {
        return get(from, to, state.ssTables());
    }

    @Override
    public void compact() {
        if (isCompacting.compareAndSet(false, true)) {
            compactor.execute(() -> {
                try {
                    forceCompact();
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    isCompacting.set(false);
                }
            });
        }
    }

    private void forceCompact() throws IOException {
        final List<SSTable> ssTables = new ArrayList<>();
        storage.iterator().forEachRemaining(ssTables::add);
        final int size = ssTables.size();
        if (size == 0 || size == 1) {
            return;
        }
        final String compactionFileName = save(() -> {
            final MergeIterator.Builder<MemorySegment, Entry<MemorySegment>> builder
                    = new MergeIterator.Builder<>(comparator);
            for (int i = 0; i < ssTables.size(); i++) {
                builder.addIterator(new WrappedIterator<>(i,
                        new SSTableIterator(ssTables.get(i), null, null, storage, comparator)));
            }
            return builder.build();
        });
        storage.compact(compactionFileName, ssTables);
    }

    @Override
    public void close() {
        compactor.close();
        storage.close();
    }
}
