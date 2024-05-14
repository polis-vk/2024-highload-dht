package ru.vk.itmo.viktorkorotkikh.io;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import ru.vk.itmo.TestBase;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.dao.compressor.LZ4Compressor;
import ru.vk.itmo.test.viktorkorotkikh.dao.decompressor.LZ4Decompressor;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.read.AbstractSSTableReader;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.read.BaseSSTableReader;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.read.CompressedSSTableReader;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.write.AbstractSSTableWriter;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.write.BaseSSTableWriter;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.write.CompressedSSTableWriter;
import ru.vk.itmo.test.viktorkorotkikh.dao.sstable.SSTableUtils;
import ru.vk.itmo.viktorkorotkikh.TestUtils;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;

class SSTableReaderWriterTest extends TestBase {
    private static final int BLOCK_4KB = 4 * 1024;

    private void testReader(
            AbstractSSTableReader reader,
            List<? extends TimestampedEntry<MemorySegment>> entries
    ) throws Exception {
        for (TimestampedEntry<MemorySegment> timestampedEntry : entries) {
            TimestampedEntry<String> stringTimestampedEntry = TestUtils.fromMemorySegmentToStringEntry(timestampedEntry);
            TimestampedEntry<MemorySegment> timestampedEntryFromReader = reader.get(timestampedEntry.key());
            TimestampedEntry<String> stringTimestampedEntryFromReader = TestUtils.fromMemorySegmentToStringEntry(timestampedEntryFromReader);
            Assertions.assertEquals(stringTimestampedEntry, stringTimestampedEntryFromReader);
        }

        Iterator<TimestampedEntry<MemorySegment>> readerIterator = reader.iterator(entries.getFirst().key(), entries.getLast().key());
        int readerEntriesSize = 0;
        while (readerIterator.hasNext()) {
            TimestampedEntry<MemorySegment> timestampedEntryFromReader = readerIterator.next();
            TimestampedEntry<String> stringTimestampedEntryFromReader = TestUtils.fromMemorySegmentToStringEntry(timestampedEntryFromReader);
            Assertions.assertTrue(readerEntriesSize < entries.size());
            TimestampedEntry<MemorySegment> timestampedEntry = entries.get(readerEntriesSize);
            TimestampedEntry<String> stringTimestampedEntry = TestUtils.fromMemorySegmentToStringEntry(timestampedEntry);
            Assertions.assertEquals(stringTimestampedEntry, stringTimestampedEntryFromReader);
            readerEntriesSize++;
        }
        Assertions.assertEquals(entries.size() - 1, readerEntriesSize);
        readerIterator = reader.iterator(entries.getLast().key(), null);
        readerEntriesSize = 0;
        while (readerIterator.hasNext()) {
            TimestampedEntry<MemorySegment> timestampedEntryFromReader = readerIterator.next();
            TimestampedEntry<String> stringTimestampedEntryFromReader = TestUtils.fromMemorySegmentToStringEntry(timestampedEntryFromReader);
            TimestampedEntry<String> stringTimestampedEntry = TestUtils.fromMemorySegmentToStringEntry(entries.getLast());
            Assertions.assertEquals(stringTimestampedEntry, stringTimestampedEntryFromReader);
            readerEntriesSize++;
        }
        Assertions.assertEquals(1, readerEntriesSize);
    }

    @Test
    void writeEntriesAndReadSameBaseTest() throws Exception {
        AbstractSSTableWriter writer = new BaseSSTableWriter();
        Path baseDir = Files.createTempDirectory("dao");
        List<? extends TimestampedEntry<MemorySegment>> entries = TestUtils.entries("k", "v", 1000).stream()
                .map(TestUtils::fromStringToMemorySegmentEntry).toList();
        writer.write(false, entries.iterator(), baseDir, 0);
        try (
                Arena arena = Arena.ofConfined();
                FileChannel ssTableFileChannel = FileChannel.open(
                        SSTableUtils.dataName(false, baseDir, 0),
                        StandardOpenOption.READ
                );
                FileChannel indexFileChannel = FileChannel.open(
                        SSTableUtils.indexName(false, baseDir, 0),
                        StandardOpenOption.READ
                );
                FileChannel compressionInfoFileChannel = FileChannel.open(
                        SSTableUtils.compressionInfoName(false, baseDir, 0),
                        StandardOpenOption.READ
                )
        ) {
            MemorySegment mappedSSTable = ssTableFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    Files.size(SSTableUtils.dataName(false, baseDir, 0)),
                    arena
            );
            MemorySegment mappedIndexFile = indexFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    Files.size(SSTableUtils.indexName(false, baseDir, 0)),
                    arena
            );
            MemorySegment mappedCompressionInfo = compressionInfoFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    Files.size(SSTableUtils.compressionInfoName(false, baseDir, 0)),
                    arena
            );
            AbstractSSTableReader reader = new BaseSSTableReader(mappedSSTable, mappedIndexFile, mappedCompressionInfo, 0);
            testReader(reader, entries);
        }
    }

    @Test
    void writeEntriesAndReadSameCompressedTest() throws Exception {
        AbstractSSTableWriter writer = new CompressedSSTableWriter(new LZ4Compressor(), BLOCK_4KB);
        Path baseDir = Files.createTempDirectory("dao");
        List<? extends TimestampedEntry<MemorySegment>> entries = TestUtils.entries("k", "v", 1000).stream()
                .map(TestUtils::fromStringToMemorySegmentEntry).toList();
        writer.write(false, entries.iterator(), baseDir, 0);
        try (
                Arena arena = Arena.ofConfined();
                FileChannel ssTableFileChannel = FileChannel.open(
                        SSTableUtils.dataName(false, baseDir, 0),
                        StandardOpenOption.READ
                );
                FileChannel indexFileChannel = FileChannel.open(
                        SSTableUtils.indexName(false, baseDir, 0),
                        StandardOpenOption.READ
                );
                FileChannel compressionInfoFileChannel = FileChannel.open(
                        SSTableUtils.compressionInfoName(false, baseDir, 0),
                        StandardOpenOption.READ
                )
        ) {
            MemorySegment mappedSSTable = ssTableFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    Files.size(SSTableUtils.dataName(false, baseDir, 0)),
                    arena
            );
            MemorySegment mappedIndexFile = indexFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    Files.size(SSTableUtils.indexName(false, baseDir, 0)),
                    arena
            );
            MemorySegment mappedCompressionInfo = compressionInfoFileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    Files.size(SSTableUtils.compressionInfoName(false, baseDir, 0)),
                    arena
            );
            AbstractSSTableReader reader
                    = new CompressedSSTableReader(mappedSSTable, mappedIndexFile, mappedCompressionInfo, new LZ4Decompressor(), 0);
            testReader(reader, entries);
        }
    }


}
