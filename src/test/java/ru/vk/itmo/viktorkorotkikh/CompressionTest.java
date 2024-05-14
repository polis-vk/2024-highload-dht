package ru.vk.itmo.viktorkorotkikh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.vk.itmo.TestBase;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.viktorkorotkikh.dao.LSMDaoImpl;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

@Timeout(10)
class CompressionTest extends TestBase {
    private static final int BLOCK_4KB = 4 * 1024;
    private static final int BLOCK_2KB = 2 * 1024;

    private static final long flushThreshold = 1 << 20; // 1 MB

    @Test
    void compareUncompressedAndCompressedDataSizeLZ4() throws IOException {
        compareUncompressedAndCompressedDataSize(Config.CompressionConfig.Compressor.LZ4);
    }

    @Test
    void compareUncompressedAndCompressedDataSizeZSTD() throws IOException {
        compareUncompressedAndCompressedDataSize(Config.CompressionConfig.Compressor.ZSTD);
    }

    private void compareUncompressedAndCompressedDataSize(Config.CompressionConfig.Compressor compressor) throws IOException {
        Path uncompressedTmp = Files.createTempDirectory("uncompressedDao");
        Path compressedTmp = Files.createTempDirectory("compressedDao");

        Config uncomprssedDaoConfig = new Config(
                uncompressedTmp,
                flushThreshold,
                Config.disableCompression()
        );
        Dao<MemorySegment, TimestampedEntry<MemorySegment>> uncompressedDao = createDao(uncomprssedDaoConfig);

        Config compressedDaoConfig = new Config(
                compressedTmp,
                flushThreshold,
                new Config.CompressionConfig(true, compressor, BLOCK_4KB)
        );
        Dao<MemorySegment, TimestampedEntry<MemorySegment>> compressedDao = createDao(compressedDaoConfig);

        List<TimestampedEntry<String>> entries = TestUtils.entries("k", "v", 1000);
        entries.forEach(stringEntry -> {
            TimestampedEntry<MemorySegment> memorySegmentTimestampedEntry = TestUtils.fromStringToMemorySegmentEntry(stringEntry);
            uncompressedDao.upsert(memorySegmentTimestampedEntry);
            compressedDao.upsert(memorySegmentTimestampedEntry);
        });

        // finish all bg processes
        uncompressedDao.close();
        compressedDao.close();

        long uncompressedSize = sizePersistentData(uncomprssedDaoConfig);
        long compressedSize = sizePersistentData(compressedDaoConfig);
        Assertions.assertTrue(compressedSize < uncompressedSize);
        cleanUp(compressedTmp);
        cleanUp(uncompressedTmp);
    }

    @Test
    void compressAndCheckDataLZ4() throws IOException {
        compressAndCheckData(Config.CompressionConfig.Compressor.LZ4);
    }

    @Test
    void compressAndCheckDataZSTD() throws IOException {
        compressAndCheckData(Config.CompressionConfig.Compressor.ZSTD);
    }

    private void compressAndCheckData(Config.CompressionConfig.Compressor compressor) throws IOException {
        Path compressedTmp = Files.createTempDirectory("compressedDao");

        Config compressedDaoConfig = new Config(
                compressedTmp,
                flushThreshold,
                new Config.CompressionConfig(true, compressor, BLOCK_4KB)
        );
        try {
            addRemoveAddAndCompact(compressedDaoConfig);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            cleanUp(compressedTmp);
        }
    }

    private void addRemoveAddAndCompact(Config daoConfig) throws IOException, InterruptedException {
        Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao = createDao(daoConfig);
        NavigableSet<TimestampedEntry<String>> values = new TreeSet<>(Comparator.comparing(TimestampedEntry::key));
        // insert some entries
        for (int i = 0; i < 50; i++) {
            TimestampedEntry<String> entry = TestUtils.entryAt(i);
            values.add(entry);
            dao.upsert(TestUtils.fromStringToMemorySegmentEntry(entry));
        }

        // remove some entries
        for (int i = 0; i < 25; i++) {
            TimestampedEntry<String> entry = TestUtils.removeEntryAt(i);
            dao.upsert(TestUtils.fromStringToMemorySegmentEntry(entry));
            TimestampedEntry<String> entryWithValue = new TimestampedEntry<>(entry.key(), TestUtils.valueAt(i), entry.timestamp());
            values.remove(entryWithValue);
        }

        TestUtils.assertSame(dao.all(), List.copyOf(values));

        // flush and check
        dao.flush();
        TestUtils.assertSame(dao.all(), List.copyOf(values));

        // re-insert entries
        for (int i = 0; i < 25; i++) {
            TimestampedEntry<String> entry = TestUtils.entryAt(i);
            values.add(entry);
            dao.upsert(TestUtils.fromStringToMemorySegmentEntry(entry));
        }

        TestUtils.assertSame(dao.all(), List.copyOf(values));

        // flush and check
        dao.flush();

        Thread.sleep(Duration.ofSeconds(1)); // 1 second should be enough to flush 10MB even to HDD
        TestUtils.assertSame(dao.all(), List.copyOf(values));

        // compact and check
        dao.compact();
        dao.close();

        dao = createDao(daoConfig);

        TestUtils.assertSame(dao.all(), List.copyOf(values));
        dao.close();
    }

    @Test
    @Timeout(20)
    void writeUncompressedReopenAndCompress() throws IOException, InterruptedException {
        Path tmp = Files.createTempDirectory("dao");

        // uncompressed dao
        Config daoConfig = new Config(
                tmp,
                flushThreshold,
                Config.disableCompression()
        );
        Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao = createDao(daoConfig);
        int valueSize = 10 * 1024 * 1024;
        int keyCount = 3;
        List<TimestampedEntry<String>> entries = TestUtils.bigValues(keyCount, valueSize);

        // 1 second should be enough to flush 10MB even to HDD
        Duration flushDelay = Duration.ofSeconds(1);

        for (TimestampedEntry<String> entry : entries) {
            dao.upsert(TestUtils.fromStringToMemorySegmentEntry(entry));
            Thread.sleep(flushDelay);
        }

        dao.close();

        // compressed dao
        dao = createDao(new Config(
                tmp,
                flushThreshold,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.LZ4, BLOCK_4KB)
        ));
        TestUtils.assertSame(dao.all(), entries);

        long uncompressedSize = sizePersistentData(daoConfig);

        dao.compact();
        Thread.sleep(flushDelay.multipliedBy(5)); // wait for the compaction to complete

        TestUtils.assertSame(dao.all(), entries);

        long compressedSize = sizePersistentData(daoConfig);
        Assertions.assertTrue(compressedSize < uncompressedSize);
        dao.close();
        cleanUp(tmp);
    }

    @Test
    void compareBlockSizes() throws IOException {
        Path compressedTmp4KB = Files.createTempDirectory("compressedDao4KB");
        Path compressedTmp2KB = Files.createTempDirectory("compressedDao2KB");
        Config compressedDao2KBConfig = new Config(
                compressedTmp4KB,
                flushThreshold,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.LZ4, BLOCK_2KB)
        );
        Dao<MemorySegment, TimestampedEntry<MemorySegment>> compressedDao2KB = createDao(compressedDao2KBConfig);

        Config compressedDao4KBConfig = new Config(
                compressedTmp2KB,
                flushThreshold,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.LZ4, BLOCK_4KB)
        );
        Dao<MemorySegment, TimestampedEntry<MemorySegment>> compressedDao4KB = createDao(compressedDao4KBConfig);
        List<TimestampedEntry<String>> entries = TestUtils.entries("k", "v", 1000);
        entries.forEach(stringEntry -> {
            TimestampedEntry<MemorySegment> memorySegmentTimestampedEntry = TestUtils.fromStringToMemorySegmentEntry(stringEntry);
            compressedDao2KB.upsert(memorySegmentTimestampedEntry);
            compressedDao4KB.upsert(memorySegmentTimestampedEntry);
        });
        // finish all bg processes
        compressedDao2KB.close();
        compressedDao4KB.close();

        long compressedSize2KB = sizePersistentData(compressedDao2KBConfig);
        long compressedSize4KB = sizePersistentData(compressedDao4KBConfig);
        Assertions.assertTrue(compressedSize4KB < compressedSize2KB);
        cleanUp(compressedTmp2KB);
        cleanUp(compressedTmp4KB);
    }

    @Test
    void multipleCompressionAlgorithms() throws IOException, InterruptedException {
        Path tmp = Files.createTempDirectory("dao");
        Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao = createDao(new Config(
                tmp,
                flushThreshold,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.LZ4, BLOCK_4KB)
        ));
        List<TimestampedEntry<String>> entries = TestUtils.entries("key", "value", 4000);

        for (int i = 0; i < 1000; i++) {
            TimestampedEntry<MemorySegment> entry = TestUtils.fromStringToMemorySegmentEntry(entries.get(i));
            dao.upsert(entry);
        }
        dao.close();

        dao = createDao(new Config(
                tmp,
                flushThreshold,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.ZSTD, BLOCK_4KB)
        ));

        for (int i = 1000; i < 2000; i++) {
            TimestampedEntry<MemorySegment> entry = TestUtils.fromStringToMemorySegmentEntry(entries.get(i));
            dao.upsert(entry);
        }
        dao.close();


        dao = createDao(new Config(
                tmp,
                flushThreshold,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.LZ4, BLOCK_2KB)
        ));

        for (int i = 2000; i < 3000; i++) {
            TimestampedEntry<MemorySegment> entry = TestUtils.fromStringToMemorySegmentEntry(entries.get(i));
            dao.upsert(entry);
        }
        dao.close();

        dao = createDao(new Config(
                tmp,
                flushThreshold,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.ZSTD, BLOCK_2KB)
        ));

        for (int i = 3000; i < 4000; i++) {
            TimestampedEntry<MemorySegment> entry = TestUtils.fromStringToMemorySegmentEntry(entries.get(i));
            dao.upsert(entry);
        }
        TestUtils.assertSame(dao.all(), entries);

        dao.compact();
        Thread.sleep(Duration.ofSeconds(5));

        TestUtils.assertSame(dao.all(), entries);
        dao.close();

        cleanUp(tmp);
    }

    private static void cleanUp(Path tmp) throws IOException {
        if (!Files.exists(tmp)) {
            return;
        }
        Files.walkFileTree(tmp, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Dao<MemorySegment, TimestampedEntry<MemorySegment>> createDao(Config daoConfig) {
        return new LSMDaoImpl(daoConfig);
    }

    public long sizePersistentData(Config config) throws IOException {
        long[] result = new long[]{0};
        Files.walkFileTree(config.basePath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                result[0] += Files.size(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return result[0];
    }
}
