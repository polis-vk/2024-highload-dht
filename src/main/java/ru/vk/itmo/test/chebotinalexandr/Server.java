package ru.vk.itmo.test.chebotinalexandr;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.chebotinalexandr.dao.NotOnlyInMemoryDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Server {
    private static final Random RANDOM = new Random();
    private static final int ENTRIES_IN_DB = 500_000;

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        ServiceConfig config = new ServiceConfig(
                8080,
                "http://localhost",
                Collections.singletonList("http://localhost"),
                Files.createTempDirectory(".")
        );

        Dao<MemorySegment, Entry<MemorySegment>> dao =
                new NotOnlyInMemoryDao(new Config(config.workingDir(), 4_194_304L));

        ExecutorService executor = new ThreadPoolExecutor(
                8,
                8,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128)
        );

        StorageServer server = new StorageServer(config, dao, executor);
        server.start();

        fillFlush(dao);
        fillManyFlushes(dao);
    }

    private static int[] getRandomArray() {
        int[] entries = new int[ENTRIES_IN_DB];
        for (int i = 0; i < ENTRIES_IN_DB; i++) {
            entries[i] = i;
        }

        int index;
        for (int i = ENTRIES_IN_DB; i > 0; i--) {
            index = RANDOM.nextInt(i + 1);
            if (index != i) {
                entries[index] ^= entries[i];
                entries[i] ^= entries[index];
                entries[index] ^= entries[i];
            }
        }
  
        return entries;
    }

    /**
     * Just fills memtable without flushing.
     */
    private static void fillMemtable(Dao<MemorySegment, Entry<MemorySegment>> dao) {
        int[] entries = getRandomArray();
        for (int entry : entries) {
            dao.upsert(entry(keyAt(entry), valueAt(entry)));
        }
    }

    /**
     * Fills memtable with one flush.
     */
    private static void fillFlush(Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        fillMemtable(dao);
        dao.flush();
    }

    /**
     * Fills dao with multiple sstables.
     */
    private static void fillManyFlushes(Dao<MemorySegment, Entry<MemorySegment>> dao) throws IOException {
        final int sstables = 100; //how many sstables dao must create
        final int flushEntries = ENTRIES_IN_DB / sstables; //how many entries in one sstable
        int[] entries = getRandomArray();

        //many flushes
        for (int entry : entries) {
            dao.upsert(entry(keyAt(entry), valueAt(entry)));
            if (entry % flushEntries == 0) {
                dao.flush();
            }
        }
    }

    private static MemorySegment keyAt(int index) {
        return MemorySegment.ofArray(("k" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static MemorySegment valueAt(int index) {
        return MemorySegment.ofArray(("v" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static Entry<MemorySegment> entry(MemorySegment key, MemorySegment value) {
        return new BaseEntry<>(key, value);
    }

}
