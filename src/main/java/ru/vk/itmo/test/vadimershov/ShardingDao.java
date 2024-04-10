package ru.vk.itmo.test.vadimershov;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.vadimershov.dao.ReferenceDao;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.FailedSharding;
import ru.vk.itmo.test.vadimershov.exceptions.NotFoundException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;
import ru.vk.itmo.test.vadimershov.hash.ConsistentHashing;
import ru.vk.itmo.test.vadimershov.hash.VirtualNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ShardingDao {

    private final int nodeCount;
    private final int nodeQuorum;

    private final Dao<MemorySegment, TimestampEntry<MemorySegment>> localDao;
    private final ConsistentHashing consistentHashing;

    public ShardingDao(ServiceConfig serviceConfig, Config daoConfig) throws IOException {
        this.nodeCount = serviceConfig.clusterUrls().size();
        this.nodeQuorum = (serviceConfig.clusterUrls().size() / 2) + 1;
        this.localDao = new ReferenceDao(daoConfig);
        this.consistentHashing = new ConsistentHashing(
                serviceConfig.selfUrl(),
                serviceConfig.clusterUrls(),
                this.localDao
        );
    }

    public ResultResponse get(String key) throws NotFoundException, DaoException {
        return consistentHashing.getLocalNode().get(key).join();
    }

    public ResultResponse get(
            String key,
            Integer ack,
            Integer from
    ) throws NotFoundException, DaoException, RemoteServiceException, FailedSharding {
        int correctAck = validate(ack, nodeQuorum);
        int correctFrom = validate(from, nodeCount);

        Collection<VirtualNode> virtualNodes = consistentHashing.findVNodes(key, correctFrom);
        List<CompletableFuture<ResultResponse>> requestsFutures = new ArrayList<>();
        for (var node : virtualNodes) {
            requestsFutures.add(node.get(key));
        }

        try {
            return waitFuture(correctAck, correctFrom, requestsFutures);
        } catch (CompletionException e) {
            throw (FailedSharding) new FailedSharding().initCause(e);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private ResultResponse waitFuture(
            int ack,
            int from,
            List<CompletableFuture<ResultResponse>> requestsFutures
    ) {
        CompletableFuture<ResultResponse> waitQuorumFuture = new CompletableFuture<>();
        AtomicInteger countFailures = new AtomicInteger(from - ack + 1);
        AtomicInteger countAcks = new AtomicInteger(ack);
        final AtomicReference<ResultResponse> response = new AtomicReference<>(new ResultResponse(-1, null, -1L));

        for (CompletableFuture<ResultResponse> requestFuture : requestsFutures) {
            requestFuture.whenComplete((resultResponse, throwable) -> {
                boolean positiveResponse = throwable == null && resultResponse.timestamp() >= 0;
                if (positiveResponse) {
                    countAcks.decrementAndGet();
                    if (response.get().timestamp() < resultResponse.timestamp()) {
                        response.set(resultResponse);
                    }
                } else {
                    countFailures.decrementAndGet();
                }

                if (countAcks.get() == 0) {
                    waitQuorumFuture.complete(response.get());
                } else if (countFailures.get() == 0) {
                    waitQuorumFuture.completeExceptionally(new FailedSharding());
                }
            });
        }
        return waitQuorumFuture.join();
    }

    public void upsert(
            String key,
            byte[] value,
            Long timestamp
    ) throws DaoException, RemoteServiceException, FailedSharding {
        consistentHashing.getLocalNode().upsert(key, value, timestamp)
                .join();
    }

    public void upsert(
            String key,
            byte[] value,
            Integer ack,
            Integer from
    ) throws DaoException, RemoteServiceException {
        int correctAck = validate(ack, nodeQuorum);
        int correctFrom = validate(from, nodeCount);

        Collection<VirtualNode> virtualNodes = consistentHashing.findVNodes(key, correctFrom);
        List<CompletableFuture<ResultResponse>> requestsFutures = new ArrayList<>();

        long timestamp = System.currentTimeMillis();
        for (var node : virtualNodes) {
            requestsFutures.add(node.upsert(key, value, timestamp));
        }

        try {
            waitFuture(correctAck, correctFrom, requestsFutures);
        } catch (CompletionException e) {
            throw (FailedSharding) new FailedSharding().initCause(e);
        }
    }

    public void delete(String key, Long timestamp) throws DaoException, RemoteServiceException {
        consistentHashing.getLocalNode().delete(key, timestamp)
                .join();
    }

    public void delete(
            String key,
            Integer ack,
            Integer from
    ) throws DaoException, RemoteServiceException, FailedSharding {
        int correctAck = validate(ack, nodeQuorum);
        int correctFrom = validate(from, nodeCount);

        Collection<VirtualNode> virtualNodes = consistentHashing.findVNodes(key, correctFrom);
        List<CompletableFuture<ResultResponse>> requestsFutures = new ArrayList<>();

        long timestamp = System.currentTimeMillis();
        for (var node : virtualNodes) {
            requestsFutures.add(node.delete(key, timestamp));
        }

        try {
            waitFuture(correctAck, correctFrom, requestsFutures);
        } catch (CompletionException e) {
            throw (FailedSharding) new FailedSharding().initCause(e);
        }
    }

    public void close() {
        try {
            this.localDao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.consistentHashing.close();
    }

    private int validate(Integer value, Integer defaultValue) {
        int correctValue = value == null ? defaultValue : value;

        if (correctValue > nodeCount || correctValue <= 0) {
            throw new FailedSharding(DaoResponse.BAD_REQUEST);
        }
        return correctValue;
    }

}
