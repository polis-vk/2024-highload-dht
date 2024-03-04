package ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable;

import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.IndexFileUtils;
import ru.vk.itmo.test.smirnovdmitrii.dao.state.StateService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Storage of SSTables. Manages SSTables.
 */
public class SSTableStorageImpl implements SSTableStorage {
    // Sorted by SSTable priority.

    private final StateService stateService;
    private final Path basePath;
    private final String indexFileName;
    private AtomicLong priorityCounter;
    private final Lock indexFileLock = new ReentrantLock();
    private final Arena arena = Arena.ofShared();

    public SSTableStorageImpl(
            final Path basePath,
            final StateService stateService,
            final String indexFileName
    ) throws IOException {
        this.basePath = basePath;
        this.stateService = stateService;
        this.indexFileName = indexFileName;
        load();
    }

    /**
     * Loading SSTables from index file.
     * @throws IOException if I/O error occurs.
     */
    private void load() throws IOException {
        final List<SSTable> ssTables = IndexFileUtils.loadIndexFile(
                basePath.resolve(indexFileName),
                basePath,
                arena
        );
        final long maxPriority = ssTables.stream().mapToLong(it -> it.priority).max().orElse(0);
        final Set<String> ssTableNames = ssTables.stream()
                .map(it -> it.path().getFileName().toString())
                .collect(Collectors.toSet());
        stateService.setSSTables(ssTables);
        priorityCounter = new AtomicLong(maxPriority + 1);
        Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (!ssTableNames.contains(file.getFileName().toString())) {
                    Files.delete(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        newIndex(IndexFileUtils.newIndexFile(ssTables));
    }

    /**
     * Adding SSTable to storage from given file name.
     * @param ssTableFileName SSTable to add.
     * @throws IOException if I/O error occurs.
     */
    @Override
    public void add(final String ssTableFileName) throws IOException {
        if (ssTableFileName == null) {
            return;
        }
        final Path ssTablePath = basePath.resolve(ssTableFileName);
        final long ssTablePriority = priorityCounter.getAndIncrement();
        final MemorySegment mappedSSTable = map(ssTablePath);
        final SSTable ssTable = new SSTable(mappedSSTable, ssTablePath, ssTablePriority);
        appendToIndex(IndexFileUtils.newSSTableRecord(ssTable));
        stateService.addSSTable(ssTable);
    }

    /**
     * Returns where SSTable was compacted. If given SSTable is alive, then unspecified.
     * @param ssTable SSTable that was compacted (dead)
     * @return SSTable where provided SSTable was compacted.
     */
    @Override
    public SSTable getCompaction(final SSTable ssTable) {
        // While we're compacting all SSTable in one, always returns last SSTable.
        final List<SSTable> currentStorage = stateService.ssTables();
        if (currentStorage.isEmpty()) {
            return null;
        }
        return currentStorage.getLast();
    }

    /**
     * Replaces Provided SSTables with SSTable from provided path.
     * @param compactionFileName file with compacted data from SSTables.
     * @param compacted representing files that was compacted.
     * @throws IOException if I/O error occurs.
     */
    @Override
    public void compact(final String compactionFileName, final List<SSTable> compacted) throws IOException {
        SSTable compaction = null;
        if (compactionFileName != null) {
            final Path compactionPath = basePath.resolve(compactionFileName);
            final MemorySegment mappedCompaction = map(compactionPath);
            long minPriority = Long.MAX_VALUE;
            // Taking minimal priority for reason if we want to compact in the mid in the future.
            for (final SSTable ssTable : compacted) {
                minPriority = Math.min(ssTable.priority(), minPriority);
            }
            compaction = new SSTable(mappedCompaction, compactionPath, minPriority);
        }
        appendToIndex(IndexFileUtils.compactionRecord(compacted, compaction));
        stateService.compact(compacted, compaction);
        for (final SSTable ssTable: compacted) {
            ssTable.kill();
            try {
                Files.delete(ssTable.path());
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private MemorySegment map(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
        }
    }

    /**
     * Adds record in index file.
     * @throws IOException if I/O error occurs.
     */
    private void appendToIndex(final byte[] recordInBytes) throws IOException {
        indexFileLock.lock();
        try {
            final byte[] oldIndexFile = Files.readAllBytes(basePath.resolve(indexFileName));
            final int oldIndexFileLength = oldIndexFile.length;
            final byte[] newIndexFile = Arrays.copyOf(
                    oldIndexFile, oldIndexFileLength + recordInBytes.length);
            System.arraycopy(recordInBytes, 0, newIndexFile, oldIndexFileLength, recordInBytes.length);
            newIndex(newIndexFile);
        } finally {
            indexFileLock.unlock();
        }
    }

    /**
     * Creates new index file from provided list.
     */
    private void newIndex(final byte[] newIndexFile) throws IOException {
        final Path indexFilePath = basePath.resolve(indexFileName);
        final Path indexFileTmpPath = basePath.resolve(indexFileName + "tmp");
        Files.write(
                indexFileTmpPath,
                newIndexFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        Files.move(
                indexFileTmpPath,
                indexFilePath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    @Override
    public void close() {
        final List<SSTable> currentStorage = stateService.ssTables();
        for (final SSTable ssTable: currentStorage) {
            ssTable.kill();
        }
        if (arena.scope().isAlive()) {
            arena.close();
        }
        stateService.setSSTables(null);
    }

    @Override
    public Iterator<SSTable> iterator() {
        return stateService.currentState().ssTables().iterator();
    }
}
