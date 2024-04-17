package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.Request;
import one.nio.util.Hash;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.emelyanovvitaliy.dao.ReferenceDao;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeDaoMediator extends DaoMediator {
    public static final String FINAL_EXECUTION_HEADER = "X-Final-Execution";
    public static final String ACK_KEY = "ack=";
    public static final String FROM_KEY = "from=";
    public static final String ID_KEY = "id=";
    protected static final int DAO_MEDIATOR_THREADS = 4 * Runtime.getRuntime().availableProcessors();
    protected static final Duration HTTP_TIMEOUT = Duration.ofMillis(100);
    protected static final int KEEP_ALIVE_TIME_MS = 1000;
    protected static final String HEADER_TRUE_STRING = ": true";
    protected static final int FLUSH_THRESHOLD_BYTES = 1 << 20; // 1 MiB
    public static final int DAO_THREADS_CAPACITY = 1024;
    protected final Executor daoExecutor;
    protected final AtomicBoolean isStopped;
    protected final DaoMediator[] daoMediators;
    protected final int[] mediatorsHashes;
    private final LocalDaoMediator localDaoMediator;

    MergeDaoMediator(Path localDir, String thisUrl, List<String> urls) throws IOException {
        isStopped = new AtomicBoolean(false);
        AtomicInteger threadCount = new AtomicInteger();
        daoExecutor = new ThreadPoolExecutor(
                DAO_MEDIATOR_THREADS,
                DAO_MEDIATOR_THREADS,
                KEEP_ALIVE_TIME_MS,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DAO_THREADS_CAPACITY),
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName("Dao-Executor-" + threadCount.getAndIncrement());
                    return thread;
                },
                (r, t) -> {
                  // ignore, made to not close HttpClient
                }
        );
        localDaoMediator = new LocalDaoMediator(
                new ReferenceDao(new Config(localDir, FLUSH_THRESHOLD_BYTES)),
                daoExecutor
        );
        daoMediators = getDaoMediators(urls, thisUrl, localDaoMediator, daoExecutor);
        mediatorsHashes = getMediatorsHashes(urls);
    }

    @Override
    void stop() {
        if (isStopped.getAndSet(true)) {
            return;
        }
        for (DaoMediator daoMediator : daoMediators) {
            daoMediator.stop();
        }
    }

    @Override
    boolean isStopped() {
        return isStopped.get();
    }

    @Override
    CompletableFuture<Boolean> put(Request request) throws IllegalArgumentException {
        return simpleReplicate(request, false);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    CompletableFuture<TimestampedEntry<MemorySegment>> get(Request request) throws IllegalArgumentException {
        if (Objects.equals(request.getHeader(FINAL_EXECUTION_HEADER), HEADER_TRUE_STRING)) {
            return localDaoMediator.get(request);
        } else {
            int ack;
            int from;
            ack = getAck(request);
            from = getFrom(request);
            if (!isAckFromCorrect(ack, from)) {
                throw new IllegalArgumentException("Wrong ack/from: " + ack + "/" + from);
            }
            String id = request.getParameter(ID_KEY);
            request.addHeader(FINAL_EXECUTION_HEADER + HEADER_TRUE_STRING);
            int currentMediatorIndex = getFirstMediatorIndex(id);
            EntryMerger entryMerger = new EntryMerger(from, ack);
            for (int i = 0; i < from; i++) {
                daoMediators[currentMediatorIndex].get(request).whenCompleteAsync(
                        entryMerger::acceptResult,
                        daoExecutor
                );
                currentMediatorIndex = (currentMediatorIndex + 1) % daoMediators.length;
            }
            return entryMerger.getCompletableFuture();
        }
    }

    @Override
    CompletableFuture<Boolean> delete(Request request) throws IllegalArgumentException {
        return simpleReplicate(request, true);
    }

    private static DaoMediator[] getDaoMediators(List<String> urls, String thisUrl, LocalDaoMediator localDaoMediator,
                                                 Executor executor) {
        DaoMediator[] mediators = new DaoMediator[urls.size()];
        int cnt = 0;
        List<String> tmpList = new ArrayList<>(urls);
        tmpList.sort(String::compareTo);
        for (String url : tmpList) {
            // create own client for each remote mediator
            // made to have more independent SelectorManager threads
            // hypothesis that didn't make things worse,
            // so I decided to leave it here (｡◕‿‿◕｡)
            HttpClient httpClient = HttpClient.newBuilder()
                    .executor(executor)
                    .connectTimeout(HTTP_TIMEOUT)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            mediators[cnt] = url.equals(thisUrl) ? localDaoMediator :
                    new RemoteDaoMediator(httpClient, url, HTTP_TIMEOUT);
            cnt++;
        }
        return mediators;
    }

    private static int[] getMediatorsHashes(List<String> urls) {
        int[] hashes = new int[urls.size()];
        int cnt = 0;
        List<String> tmpList = new ArrayList<>(urls);
        tmpList.sort(String::compareTo);
        for (String url : tmpList) {
            // cantor pairing function works nicely only with non-negatives
            hashes[cnt++] = Math.abs(Hash.murmur3(url));
        }
        return hashes;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<Boolean> simpleReplicate(Request request, boolean delete)
            throws IllegalArgumentException {
        if (Objects.equals(request.getHeader(FINAL_EXECUTION_HEADER), HEADER_TRUE_STRING)) {
            return delete ? localDaoMediator.delete(request) : localDaoMediator.put(request);
        }
        int ack;
        int from;
        ack = getAck(request);
        from = getFrom(request);
        if (!isAckFromCorrect(ack, from)) {
            throw new IllegalArgumentException("Wrong ack/from: " + ack + "/" + from);
        }
        request.addHeader(FINAL_EXECUTION_HEADER + HEADER_TRUE_STRING);
        String id = request.getParameter(ID_KEY);
        int currentMediatorIndex = getFirstMediatorIndex(id);
        BoolMerger boolMerger = new BoolMerger(from, ack);
        for (int i = 0; i < from; i++) {
            CompletableFuture<Boolean> res = delete ? daoMediators[currentMediatorIndex].delete(request)
                    : daoMediators[currentMediatorIndex].put(request);
            res.whenCompleteAsync(boolMerger::acceptResult, daoExecutor);
            currentMediatorIndex = (currentMediatorIndex + 1) % daoMediators.length;
        }
        return boolMerger.getCompletableFuture();
    }

    private int getAck(Request request) throws NumberFormatException {
        String rawAck = request.getParameter(ACK_KEY);
        return rawAck == null ? (daoMediators.length / 2) + 1 : Integer.parseInt(rawAck);
    }

    private int getFrom(Request request) throws NumberFormatException {
        String rawFrom = request.getParameter(FROM_KEY);
        return rawFrom == null ? daoMediators.length : Integer.parseInt(rawFrom);
    }

    private boolean isAckFromCorrect(int ack, int from) {
        return ack > 0 && from > 0 && ack <= from && from <= daoMediators.length;
    }

    private int getFirstMediatorIndex(String key) {
        int maxHash = Integer.MIN_VALUE;
        int choosen = 0;
        int keyHash = Math.abs(Hash.murmur3(key));
        for (int i = 0; i < mediatorsHashes.length; i++) {
            int totalHash = (mediatorsHashes[i] + keyHash) * (mediatorsHashes[i] + keyHash + 1) / 2 + keyHash;
            if (totalHash > maxHash) {
                maxHash = totalHash;
                choosen = i;
            }
        }
        return choosen;
    }
}
