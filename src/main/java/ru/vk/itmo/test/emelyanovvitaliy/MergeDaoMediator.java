package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.HttpClient;
import one.nio.http.Request;
import one.nio.net.ConnectionString;
import one.nio.util.Hash;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.emelyanovvitaliy.dao.ReferenceDao;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class MergeDaoMediator extends DaoMediator {
    public static final String FINAL_EXECUTION_HEADER = "X-Final-Execution: ";
    public static final String ACK_KEY = "ack=";
    public static final String FROM_KEY = "from=";
    protected static final String TRUE_STRING = "true";
    protected static final int FLUSH_THRESHOLD_BYTES = 1 << 24; // 16 MiB
    public static final String ID_KEY = "id=";
    protected final AtomicBoolean isStopped = new AtomicBoolean(false);
    protected final DaoMediator[] daoMediators;
    protected final int[] mediatorsHashes;
    private final LocalDaoMediator localDaoMediator;

    MergeDaoMediator(Path localDir, String thisUrl, List<String> urls) throws IOException {
        localDaoMediator = new LocalDaoMediator(
                new ReferenceDao(
                        new Config(
                                localDir,
                                FLUSH_THRESHOLD_BYTES
                        )
                )
        );
        daoMediators = getDaoMediators(urls, thisUrl, localDaoMediator);
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
    boolean put(Request request) throws IllegalArgumentException {
        return simpleReplicate(request, false);
    }

    @Override
    TimestampedEntry<MemorySegment> get(Request request) throws IllegalArgumentException {
        if (Objects.equals(request.getHeader(FINAL_EXECUTION_HEADER), TRUE_STRING)) {
            return localDaoMediator.get(request);
        } else {
            int ack;
            int from;
            int answered = 0;
            try {
                ack = getAck(request);
                from = getFrom(request);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(e);
            }
            if (!isAckFromCorrect(ack, from)) {
                throw new IllegalArgumentException("Wrong ack/from: " + ack + "/" + from);
            }
            String id = request.getParameter(ID_KEY);
            request.addHeader(FINAL_EXECUTION_HEADER + TRUE_STRING);
            int currentMediatorIndex = getFirstMediatorIndex(id);
            TimestampedEntry<MemorySegment> lastEntry = null;
            for (int i = 0; i < from; i++) {
                TimestampedEntry<MemorySegment> entry =
                        daoMediators[currentMediatorIndex].get(request);
                lastEntry = findLastEntry(lastEntry, entry);
                if (entry != null) {
                    answered += 1;
                }
                currentMediatorIndex = (currentMediatorIndex + 1) % daoMediators.length;
            }
            return answered >= ack ? lastEntry : null;
        }
    }

    @Override
    boolean delete(Request request) throws IllegalArgumentException {
        return simpleReplicate(request, true);
    }

    private static DaoMediator[] getDaoMediators(List<String> urls, String thisUrl, LocalDaoMediator localDaoMediator) {
        DaoMediator[] mediators = new DaoMediator[urls.size()];
        int cnt = 0;
        List<String> tmpList = new ArrayList<>(urls);
        tmpList.sort(String::compareTo);
        for (String url : tmpList) {
            if (url.equals(thisUrl)) {
                mediators[cnt] = localDaoMediator;
            } else {
                mediators[cnt] = new RemoteDaoMediator(new HttpClient(new ConnectionString(url)));
            }
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

    private static TimestampedEntry<MemorySegment> findLastEntry(
            TimestampedEntry<MemorySegment> a,
            TimestampedEntry<MemorySegment> b
    ) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.timestamp() > b.timestamp()) {
            return a;
        }
        return b;
    }

    private boolean simpleReplicate(Request request, boolean delete)
            throws IllegalArgumentException {
        if (Objects.equals(request.getHeader(FINAL_EXECUTION_HEADER), TRUE_STRING)) {
            if (delete) {
                return localDaoMediator.delete(request);
            } else {
                return localDaoMediator.put(request);
            }
        }
        int ack;
        int from;
        int answered = 0;
        try {
            ack = getAck(request);
            from = getFrom(request);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(e);
        }
        if (!isAckFromCorrect(ack, from)) {
            throw new IllegalArgumentException("Wrong ack/from: " + ack + "/" + from);
        }
        request.addHeader(FINAL_EXECUTION_HEADER + TRUE_STRING);
        String id = request.getParameter(ID_KEY);
        int currentMediatorIndex = getFirstMediatorIndex(id);
        for (int i = 0; i < from; i++) {
            boolean res;
            if (delete) {
                res = daoMediators[currentMediatorIndex].delete(request);
            } else {
                res = daoMediators[currentMediatorIndex].put(request);
            }
            answered += res ? 1 : 0;
            currentMediatorIndex = (currentMediatorIndex + 1) % daoMediators.length;
        }
        return answered >= ack;
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
        for (int i = 0; i < mediatorsHashes.length; i++) {
            // cantor pairing function works nicely only with non-negatives
            int keyHash = Math.abs(Hash.murmur3(key));
            int totalHash = (mediatorsHashes[i] + keyHash) * (mediatorsHashes[i] + keyHash + 1) / 2 + keyHash;
            if (totalHash > maxHash) {
                maxHash = totalHash;
                choosen = i;
            }
        }
        return choosen;
    }

}
