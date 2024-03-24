package ru.vk.itmo.test.shishiginstepan.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;

public final class BinarySearchSSTableWriter {
    private static final ThreadLocal<Path> sstPath = new ThreadLocal<>();
    private static final ThreadLocal<Path> tempSSTPath = new ThreadLocal<>();
    private static final ThreadLocal<Path> indexPath = new ThreadLocal<>();
    private static final ThreadLocal<Path> tempIndexPath = new ThreadLocal<>();

    private static class SSTableCreationException extends RuntimeException {
        public SSTableCreationException(Throwable cause) {
            super(cause);
        }
    }

    private BinarySearchSSTableWriter() {
    }

    public static BinarySearchSSTable writeSSTable(
            Iterable<Entry<MemorySegment>> entries,
            Path path,
            int id,
            Arena arena
    ) {
        sstPath.set(Path.of(path.toAbsolutePath() + "/sstable_" + id));
        tempSSTPath.set(Path.of(
                path.toAbsolutePath() + "/tmp-sstable" + ThreadLocalRandom.current().nextInt(0, 1_000_000)
        ));
        indexPath.set(Path.of(path.toAbsolutePath() + "/sstable_" + id + "_index"));
        tempIndexPath.set(Path.of(
                path.toAbsolutePath() + "/tmp-index" + ThreadLocalRandom.current().nextInt(0, 1_000_000)
        ));
        MemorySegment tableSegment;
        MemorySegment indexSegment;
        long dataSize = 0;
        long indexSize = 0;
        for (var entry : entries) {
            dataSize += entry.key().byteSize();

            if (entry.value() != null) {
                dataSize += entry.value().byteSize();
            }
            indexSize += Long.BYTES * 2;
        }
        try (var fileChannel = FileChannel.open(
                tempSSTPath.get(),
                StandardOpenOption.READ,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            tableSegment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, dataSize, arena);
        } catch (IOException e) {
            throw new SSTableCreationException(e);
        }

        try (var fileChannel = FileChannel.open(
                tempIndexPath.get(),
                StandardOpenOption.READ,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            indexSegment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, indexSize, arena);
        } catch (IOException e) {
            throw new SSTableCreationException(e);
        }
        writeEntries(entries, tableSegment, indexSegment);
        try {
            Files.move(tempIndexPath.get(), indexPath.get(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SSTableCreationException(e);
        }

        try {
            Files.move(tempSSTPath.get(), sstPath.get(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new SSTableCreationException(e);
        }

        final Path newTablePath = sstPath.get();
        sstPath.remove();
        tempSSTPath.remove();
        indexPath.remove();
        tempIndexPath.remove();
        return new BinarySearchSSTable(newTablePath, arena);
    }

    private static void writeEntries(
            Iterable<Entry<MemorySegment>> entries,
            MemorySegment tableSegment,
            MemorySegment indexSegment
    ) {
        long tableOffset = 0;
        long indexOffset = 0;
        for (var entry : entries) {
            indexSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, tableOffset);
            indexOffset += ValueLayout.JAVA_LONG_UNALIGNED.byteSize();

            MemorySegment.copy(entry.key(), 0, tableSegment, tableOffset, entry.key().byteSize());
            tableOffset += entry.key().byteSize();
            if (entry.value() == null) {
                indexSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, tombstone(tableOffset));
                indexOffset += ValueLayout.JAVA_LONG_UNALIGNED.byteSize();
                continue;
            }
            indexSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, indexOffset, tableOffset);
            indexOffset += ValueLayout.JAVA_LONG_UNALIGNED.byteSize();

            MemorySegment.copy(entry.value(), 0, tableSegment, tableOffset, entry.value().byteSize());
            tableOffset += entry.value().byteSize();
        }
    }

    private static long tombstone(long offset) {
        return 1L << 63 | offset;
    }

}
