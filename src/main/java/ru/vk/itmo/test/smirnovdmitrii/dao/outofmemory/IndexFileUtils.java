package ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory;

import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTable;
import ru.vk.itmo.test.smirnovdmitrii.dao.util.exceptions.CorruptedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class IndexFileUtils {

    private static final byte DELETE = 0b01;
    private static final byte NEW_SSTABLE = 0b10;
    private static final byte COMPACTION = 0b11;
    private static final int FILE_NAME_LENGTH = 36;

    private IndexFileUtils() {
    }

    public static byte[] compactionRecord(final List<SSTable> compacted, final SSTable compaction) {
        int byteCount = 1;
        int compactedSize = compacted.size();
        byteCount += compactedSize * Long.BYTES + Long.BYTES;
        if (compaction != null) {
            byteCount += Long.BYTES + FILE_NAME_LENGTH;
        }

        final MemorySegment memorySegment = MemorySegment.ofArray(new byte[byteCount]);
        long index = 0;
        if (compaction == null) {
            setOpAndLong(memorySegment, DELETE, compactedSize);
            index += 1 + Long.BYTES;
        } else {
            setOpAndLong(memorySegment, COMPACTION, compactedSize + 1);
            setFileName(memorySegment, compaction, Long.BYTES + 1);
            memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED,
                    Long.BYTES + 1 + FILE_NAME_LENGTH, compaction.priority());
            index += 1 + 2 * Long.BYTES + FILE_NAME_LENGTH;
        }
        for (final SSTable ssTable : compacted) {
            memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, index, ssTable.priority());
            index += Long.BYTES;
        }
        return memorySegment.toArray(ValueLayout.JAVA_BYTE);
    }

    public static byte[] newSSTableRecord(final SSTable ssTable) {
        final MemorySegment memorySegment = MemorySegment.ofArray(new byte[1 + Long.BYTES + FILE_NAME_LENGTH]);
        setOpAndLong(memorySegment, NEW_SSTABLE, ssTable.priority());
        setFileName(memorySegment, ssTable, 1 + Long.BYTES);
        return memorySegment.toArray(ValueLayout.JAVA_BYTE);
    }

    private static void setOpAndLong(
            final MemorySegment memorySegment,
            final byte op,
            final long longValue
    ) {
        memorySegment.set(ValueLayout.JAVA_BYTE, 0, op);
        memorySegment.set(ValueLayout.JAVA_LONG_UNALIGNED, 1, longValue);
    }

    private static void setFileName(
            final MemorySegment memorySegment,
            final SSTable ssTable,
            final int offset
    ) {
        final byte[] bytes = ssTable.path().getFileName().toString().getBytes(StandardCharsets.UTF_8);
        MemorySegment.copy(bytes, 0, memorySegment, ValueLayout.JAVA_BYTE, offset, bytes.length);
    }

    public static List<SSTable> loadIndexFile(
            final Path indexFilePath,
            final Path basePath,
            final Arena sstableArena
    ) throws IOException {
        final Map<Long, String> ssTablesMap = new HashMap<>();
        try (Arena arena = Arena.ofConfined()) {
            try (FileChannel channel = FileChannel.open(indexFilePath)) {
                final MemorySegment mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena);
                processIndexFile(ssTablesMap, mapped);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        final List<SSTable> ssTables = new ArrayList<>();
        for (final Map.Entry<Long, String> priorityToName : ssTablesMap.entrySet()) {
            ssTables.add(readSSTable(sstableArena, basePath, priorityToName.getValue(), priorityToName.getKey()));
        }
        return ssTables.reversed();
    }

    private static void processIndexFile(Map<Long, String> ssTablesMap, MemorySegment mapped) {
        long index = 0;
        while (index < mapped.byteSize()) {
            final byte op = mapped.get(ValueLayout.JAVA_BYTE, index);
            index++;
            long currentPriority = -1;
            String currentName = null;
            if (op == NEW_SSTABLE) {
                final long priority = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, index);
                index += Long.BYTES;
                currentPriority = priority;
                currentName = readFileName(mapped, index);
                index += FILE_NAME_LENGTH;
            } else if (op == COMPACTION || op == DELETE) {
                final long fileCount = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, index);
                index += Long.BYTES;
                int startIndex = 0;
                if (op == COMPACTION) {
                    startIndex = 1;
                    currentName = readFileName(mapped, index);
                    index += FILE_NAME_LENGTH;
                    currentPriority = mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, index);
                    index += Long.BYTES;
                }
                for (int i = startIndex; i < fileCount; i++) {
                    ssTablesMap.remove(mapped.get(ValueLayout.JAVA_LONG_UNALIGNED, index));
                    index += Long.BYTES;
                }
            } else {
                throw new CorruptedException("corrupted");
            }
            if (currentPriority != -1) {
                ssTablesMap.put(currentPriority, currentName);
            }
        }
    }

    private static String readFileName(MemorySegment mapped, long index) {
        return new String(mapped.asSlice(index, FILE_NAME_LENGTH)
                .toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static SSTable readSSTable(
            final Arena arena,
            final Path basePath,
            final String ssTableName,
            final long priority
    ) throws IOException {
        final Path tablePath = basePath.resolve(ssTableName);
        try (FileChannel channel = FileChannel.open(tablePath, StandardOpenOption.READ)) {
            return new SSTable(
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena),
                    tablePath,
                    priority
            );
        }
    }

    public static byte[] newIndexFile(
            final List<SSTable> ssTables
    ) {
        final int ssTablesSize = ssTables.size();
        final int byteCount = ssTablesSize * (Long.BYTES + FILE_NAME_LENGTH) + ssTablesSize;
        final MemorySegment segment = MemorySegment.ofArray(new byte[byteCount]);
        int index = 0;
        for (final SSTable ssTable : ssTables) {
            segment.set(ValueLayout.JAVA_BYTE, index, NEW_SSTABLE);
            segment.set(ValueLayout.JAVA_LONG_UNALIGNED, index + 1, ssTable.priority());
            setFileName(segment, ssTable, Long.BYTES + index + 1);
            index += FILE_NAME_LENGTH + Long.BYTES + 1;
        }
        return segment.toArray(ValueLayout.JAVA_BYTE);
    }

}
