package ru.vk.itmo.test.kislovdanil.dao.sstable;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.iterators.DatabaseIterator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public class SSTable implements Comparable<SSTable>, Iterable<Entry<MemorySegment>> {
    // Contains offset and size for every key and every value in index file
    MemorySegment summaryFile;
    private static final String SUMMARY_FILENAME = "summary";
    // Contains keys
    MemorySegment indexFile;
    private static final String INDEX_FILENAME = "index";
    // Contains values
    MemorySegment dataFile;
    private static final String DATA_FILENAME = "data";
    final Comparator<MemorySegment> memSegComp;
    private final Arena filesArena;
    private final long tableId;
    private final Path ssTablePath;

    final long size;

    /* In case deletion while compaction of this table field would link to table with compacted data.
    Necessary for iterators created before compaction. */
    // Gives a guarantee that SSTable files wouldn't be deleted while reading

    public long getTableId() {
        return tableId;
    }

    public SSTable(Path basePath, Comparator<MemorySegment> memSegComp, long tableId, Arena filesArena)
            throws IOException {
        this(basePath, memSegComp, tableId, null, false, filesArena);
    }

    public SSTable(Path basePath, Comparator<MemorySegment> memSegComp, long tableId,
                   Iterator<Entry<MemorySegment>> entriesContainer, Arena filesArena) throws IOException {
        this(basePath, memSegComp, tableId, entriesContainer, true, filesArena);
    }

    private SSTable(Path basePath, Comparator<MemorySegment> memSegComp, long tableId,
                    Iterator<Entry<MemorySegment>> entriesContainer,
                    boolean rewrite, Arena filesArena) throws IOException {
        this.tableId = tableId;
        this.filesArena = filesArena;
        this.ssTablePath = basePath.resolve(Long.toString(tableId));
        this.memSegComp = memSegComp;
        Path summaryFilePath = this.ssTablePath.resolve(SUMMARY_FILENAME);
        Path indexFilePath = this.ssTablePath.resolve(INDEX_FILENAME);
        Path dataFilePath = this.ssTablePath.resolve(DATA_FILENAME);
        if (rewrite) {
            write(entriesContainer, summaryFilePath, indexFilePath, dataFilePath);
        } else {
            readOld(summaryFilePath, indexFilePath, dataFilePath);
        }

        this.summaryFile = this.summaryFile.asReadOnly();
        this.indexFile = this.indexFile.asReadOnly();
        this.dataFile = this.dataFile.asReadOnly();

        this.size = (this.summaryFile.byteSize() / Metadata.SIZE);
    }

    private void readOld(Path summaryFilePath, Path indexFilePath, Path dataFilePath) throws IOException {
        createIfNotExist(summaryFilePath);
        createIfNotExist(indexFilePath);
        createIfNotExist(dataFilePath);
        summaryFile = mapFile(Files.size(summaryFilePath), summaryFilePath);
        indexFile = mapFile(Files.size(indexFilePath), indexFilePath);
        dataFile = mapFile(Files.size(dataFilePath), dataFilePath);
    }

    private void createIfNotExist(Path file) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            Files.createFile(file);
        }
    }

    private void prepareForWriting(Path file) throws IOException {
        if (Files.exists(file)) {
            Files.delete(file);
        }
        Files.createDirectories(file.getParent());
        Files.createFile(file);

    }

    private MemorySegment mapFile(long size, Path filePath) throws IOException {
        try (RandomAccessFile raFile = new RandomAccessFile(filePath.toString(), "rw")) {
            return raFile.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size, filesArena);
        }
    }

    private void writeEntry(Entry<MemorySegment> entry,
                            long summaryOffset, long indexOffset, long dataOffset) {
        MemorySegment.copy(entry.key(), 0, indexFile, indexOffset, entry.key().byteSize());
        if (entry.value() != null) {
            MemorySegment.copy(entry.value(), 0, dataFile, dataOffset, entry.value().byteSize());
        }
        Metadata.writeEntryMetadata(entry, summaryFile, summaryOffset, indexOffset, dataOffset);
    }

    private long[] getFilesSize(Iterable<Entry<MemorySegment>> entriesContainer) {
        long indexSize = 0;
        long dataSize = 0;
        long summarySize = 0;
        for (Entry<MemorySegment> entry : entriesContainer) {
            indexSize += entry.key().byteSize();
            dataSize += entry.value() == null ? 0 : entry.value().byteSize();
            summarySize += Metadata.SIZE;
        }
        return new long[]{summarySize, indexSize, dataSize};
    }

    // Sequentially writes every entity data in SStable keeping files data consistent
    private void write(Iterator<Entry<MemorySegment>> entryIterator,
                       Path summaryFilePath, Path indexFilePath, Path dataFilePath) throws IOException {
        prepareForWriting(summaryFilePath);
        prepareForWriting(indexFilePath);
        prepareForWriting(dataFilePath);

        List<Entry<MemorySegment>> entries = new ArrayList<>();
        while (entryIterator.hasNext()) {
            entries.add(entryIterator.next());
        }

        long[] filesSize = getFilesSize(entries);

        summaryFile = mapFile(filesSize[0], summaryFilePath);
        indexFile = mapFile(filesSize[1], indexFilePath);
        dataFile = mapFile(filesSize[2], dataFilePath);

        long currentSummaryOffset = 0;
        long currentIndexOffset = 0;
        long currentDataOffset = 0;
        for (Entry<MemorySegment> entry : entries) {
            MemorySegment value = entry.value();
            value = value == null ? filesArena.allocate(0) : value;
            MemorySegment key = entry.key();
            writeEntry(entry, currentSummaryOffset, currentIndexOffset, currentDataOffset);
            currentDataOffset += value.byteSize();
            currentIndexOffset += key.byteSize();
            currentSummaryOffset += Metadata.SIZE;
        }
    }

    // Deletes all SSTable files from disk. Don't use object after invocation of this method!
    public void deleteFromDisk() throws IOException {
        Files.delete(ssTablePath.resolve(SUMMARY_FILENAME));
        Files.delete(ssTablePath.resolve(INDEX_FILENAME));
        Files.delete(ssTablePath.resolve(DATA_FILENAME));
        Files.delete(ssTablePath);
    }

    Range readRange(MemorySegment segment, long offset) {
        return new Range(segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset),
                segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset + Long.BYTES));
    }

    /* Binary search in summary and index files. Returns index of least record greater than key or equal.
    Returns -1 if no such key */
    long findByKey(MemorySegment key) {
        long left = -1; // Always less than key
        long right = size; // Always greater or equal than key
        while (right - left > 1) {
            long middle = (right + left) / 2;
            Metadata currentEntryMetadata = new Metadata(middle, this);
            MemorySegment curKey = currentEntryMetadata.readKey();
            int compRes = memSegComp.compare(key, curKey);
            if (compRes <= 0) {
                right = middle;
            } else {
                left = middle;
            }
        }
        return right == size ? -1 : right; // If right == size, then key is bigger than any SSTable key
    }

    private long findByKeyExact(MemorySegment key) {
        long goe = findByKey(key);
        if (goe == -1 || memSegComp.compare(readEntry(goe).key(), key) != 0) return -1;
        return goe;
    }

    Entry<MemorySegment> readEntry(long index) {
        Metadata metadata = new Metadata(index, this);
        MemorySegment key = metadata.readKey();
        MemorySegment value = metadata.readValue();
        return new BaseEntry<>(key, value);
    }

    public Entry<MemorySegment> find(MemorySegment key) throws IOException {
        long entryId = findByKeyExact(key);
        if (entryId == -1) return null;
        return readEntry(entryId);
    }

    public DatabaseIterator getRange(MemorySegment from, MemorySegment to) {
        return new SSTableIterator(from, to, this);
    }

    public DatabaseIterator getRange() {
        return getRange(null, null);
    }

    @Override
    public Iterator<Entry<MemorySegment>> iterator() {
        return getRange();
    }

    // The less the ID, the less the table
    @Override
    public int compareTo(SSTable o) {
        return Long.compare(this.tableId, o.tableId);
    }

    // Describes offset and size of any data segment
    static class Range {
        public long offset;
        public long length;

        public Range(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }
    }
}
