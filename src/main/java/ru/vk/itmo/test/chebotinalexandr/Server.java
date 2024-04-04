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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Server {
    private static final Random RANDOM = new Random();
    private static final int ENTRIES_IN_DB = 500_000;
    private static final long FLUSH_THRESHOLD_BYTES = 4_194_3040L;
    private static final int BASE_PORT = 8080;
    private static final int NODES = 3;
    private static final int POOL_SIZE = 20;
    private static final int QUEUE_CAPACITY = 256;

    private Server() {

    }

    public static void main(String[] args) throws IOException {
        List<String> clusterUrls = new ArrayList<>();
        Dao<MemorySegment, Entry<MemorySegment>>[] daoCluster = new Dao[NODES];

        for (int i = 0; i < NODES; i++) {
            int port = BASE_PORT + i;
            clusterUrls.add("http://localhost:" + port);
        }

        for (int i = 0; i < NODES; i++) {
            int port = BASE_PORT + i;

            ServiceConfig config = new ServiceConfig(
                    port,
                    "http://localhost:" + port,
                    clusterUrls,
                    Files.createDirectory(Paths.get("/Users/axothy/dao/tmp." + port))
            );

            Dao<MemorySegment, Entry<MemorySegment>> dao =
                    new NotOnlyInMemoryDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
            daoCluster[i] = dao;
            ExecutorService executor = new ThreadPoolExecutor(
                    POOL_SIZE,
                    POOL_SIZE,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY)
            );
            StorageServer server = new StorageServer(config, dao, executor);
            server.start();

        }

        fillClusterNodesWithMultipleFlush(daoCluster);
    }

    private static int[] getRandomArray() {
        int[] entries = new int[ENTRIES_IN_DB];
        for (int i = 0; i < ENTRIES_IN_DB; i++) {
            entries[i] = i;
        }

        int index;
        for (int i = ENTRIES_IN_DB - 1; i > 0; i--) {
            index = RANDOM.nextInt(i + 1);
            if (index != i) {
                entries[index] ^= entries[i];
                entries[i] ^= entries[index];
                entries[index] ^= entries[i];
            }
        }
        return entries;
    }

    private static void fillClusterNodesWithMultipleFlush(Dao... daoCluster) throws IOException {
        final int sstables = 100; //how many sstables dao must create
        final int flushEntries = ENTRIES_IN_DB / sstables; //how many entries in one sstable
        final int[] entries = getRandomArray();

        //only for GET tests with from = 3
        int count = 0;
        for (int entry : entries) {
            //upsert entry
            for (int node = 0; node < NODES; node++) {
                daoCluster[node].upsert(entry(keyAt(entry), valueAt(entry)));
            }
            count++;

            //flush nodes
            if (count == flushEntries) {
                for (Dao dao : daoCluster) {
                    dao.flush();
                }
                count = 0;
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
