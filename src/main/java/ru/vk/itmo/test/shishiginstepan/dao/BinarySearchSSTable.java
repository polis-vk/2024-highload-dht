package ru.vk.itmo.test.shishiginstepan.dao;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

public class BinarySearchSSTable implements SSTable<MemorySegment, EntryWithTimestamp<MemorySegment>> {
    private long tableSize;
    private long indexSize;
    private final MemorySegment tableSegment;
    private final MemorySegment indexSegment;
    public int id;
    public AtomicBoolean closed = new AtomicBoolean(false);
    public AtomicBoolean inCompaction = new AtomicBoolean(false);
    public final Path tablePath;
    public final Path indexPath;

    private static class SSTableRWException extends RuntimeException {
        public SSTableRWException(Throwable cause) {
            super(cause);
        }
    }

    private static class ClosedSSTableAccess extends RuntimeException {
        public ClosedSSTableAccess() {
            super();
        }
    }

    BinarySearchSSTable(Path path, Arena arena) {
        this.id = Integer.parseInt(path.getFileName().toString().substring(8));
        tablePath = path;
        indexPath = Paths.get(path + "_index");

        try {
            if (Files.exists(tablePath)) {
                this.tableSize = Files.size(tablePath);
            }
            if (Files.exists(indexPath)) {
                this.indexSize = Files.size(indexPath);
            }
        } catch (IOException e) {
            throw new SSTableRWException(e);
        }
        try (FileChannel fileChannel = FileChannel.open(tablePath, StandardOpenOption.READ)) {
            this.tableSegment = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, tableSize, arena);
        } catch (IOException e) {
            throw new SSTableRWException(e);
        }
        try (FileChannel fileChannel = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            this.indexSegment = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, indexSize, arena);
        } catch (IOException e) {
            throw new SSTableRWException(e);
        }
    }

    private long searchEntryPosition(MemorySegment key, boolean exact) {
        long l = 0;
        long r = this.indexSize / (Long.BYTES * 2) - 1;
        long m;
        while (l <= r) {
            m = l + (r - l) / 2;

            long keyOffset = getKeyOffset(m);
            long valOffset = normalize(getValOffset(m));
            long keyEnd = valOffset - ValueLayout.JAVA_LONG_UNALIGNED.byteSize();
            long mismatch = MemorySegment.mismatch(key, 0, key.byteSize(), tableSegment, keyOffset, keyEnd);
            if (mismatch == -1) {
                return m;
            }
            if (mismatch == valOffset - keyOffset) {
                l = m + 1;
                continue;
            }
            if (mismatch == key.byteSize()) {
                r = m - 1;
                continue;
            }
            byte b1 = key.get(ValueLayout.JAVA_BYTE, mismatch);
            byte b2 = tableSegment.get(ValueLayout.JAVA_BYTE, keyOffset + mismatch);
            int keysBytesCompared = Byte.compare(b1, b2);
            if (keysBytesCompared < 0) {
                r = m - 1;
            } else {
                l = m + 1;
            }
        }
        return exact ? -1 : l;
    }

    @Override
    public EntryWithTimestamp<MemorySegment> get(MemorySegment key) {
        MemorySegment val;
        long m = this.searchEntryPosition(key, true);
        if (m == -1) return null;
        long valOffset = getValOffset(m);
        long recordEnd = getRecordEnd(m);
        long timestampOffset = getTimestampOffset(valOffset);
        val = valOffset < 0 ? null : tableSegment.asSlice(valOffset, recordEnd - valOffset);
        long timestamp = tableSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, timestampOffset);
        return new EntryWithTimestamp<>(key, val, timestamp);
    }

    @Override
    public Iterator<EntryWithTimestamp<MemorySegment>> scan(MemorySegment keyFrom, MemorySegment keyTo) {
        long startIndex;
        long endIndex;
        if (keyFrom == null) {
            startIndex = 0;
        } else {
            startIndex = this.searchEntryPosition(keyFrom, false);
        }
        if (keyTo == null) {
            endIndex = this.indexSize / (Long.BYTES * 2);
        } else {
            endIndex = this.searchEntryPosition(keyTo, false);
        }
        return iterator(
                startIndex,
                endIndex
        );
    }

    private static long normalize(long value) {
        return value & ~(1L << 63);
    }

    private long getKeyOffset(long recordIndex) {
        return indexSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, recordIndex * Long.BYTES * 2);
    }

    private long getTimestampOffset(long valOffset) { // таймстемп - лонг который лежит прямо перед значением record`а.
        return normalize(valOffset) - ValueLayout.JAVA_LONG_UNALIGNED.byteSize();
    }

    private long getValOffset(long recordIndex) {
        return indexSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, recordIndex * Long.BYTES * 2 + Long.BYTES);
    }

    private long getRecordEnd(long recordIndex) {
        if ((recordIndex + 1) * Long.BYTES * 2 == indexSize) {
            // Случай когда мы не можем посчитать размер значения тк не имеем оффсета следующего за ним элемента
            return tableSize;
        } else {
            return indexSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, (recordIndex + 1) * Long.BYTES * 2);
        }
    }

    private Iterator<EntryWithTimestamp<MemorySegment>> iterator(long startEntryIndex, long endEntryIndex) {
        return new Iterator<>() {
            long currentEntryIndex = startEntryIndex;

            @Override
            public boolean hasNext() {
                return this.currentEntryIndex != endEntryIndex;
            }

            @Override
            public EntryWithTimestamp<MemorySegment> next() {
                var keyOffset = getKeyOffset(currentEntryIndex);
                var valOffset = getValOffset(currentEntryIndex);
                var timeStampOffset = getTimestampOffset(valOffset);
                long nextOffset = getRecordEnd(currentEntryIndex);
                this.currentEntryIndex++;
                return new EntryWithTimestamp<>(
                        tableSegment.asSlice(keyOffset, normalize(valOffset) - keyOffset),
                        valOffset < 0
                                ?
                                null
                                :
                                tableSegment.asSlice(
                                        normalize(valOffset),
                                        nextOffset - normalize(valOffset)
                                ),
                        tableSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, timeStampOffset)
                );
            }
        };
    }

    public void close() {
        if (closed.get()) throw new ClosedSSTableAccess();
        closed.compareAndSet(false, true);
    }
}
