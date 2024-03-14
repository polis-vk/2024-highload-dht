package ru.vk.itmo.test.proninvalentin.failure_limiter;

import ru.vk.itmo.test.proninvalentin.Utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class FailureLimiter {
    private final AtomicIntegerArray failuresPerNode;
    private final int maxFailureNumber;
    private final Map<String, Integer> nodeToIndex;

    private final ScheduledExecutorService failuresResetor;
    private final AtomicBoolean[] resettingNodes;
    // The minimum for node failures resetting
    private static final int MIN_RESETTING_DELAY_MILLIS = 1000;
    private static final int MIN_RESETTING_PERIOD_MILLIS = 100;
    private static final int MAX_RESETTING_PERIOD_MILLIS = 300;
    private static final Random rnd = new Random();

    public FailureLimiter(FailureLimiterConfig config) {

        List<String> clusterUrls = config.clusterUrls();
        int maxFailureNumber = config.MaxFailureNumber();
        int nodesNumber = clusterUrls.size();

        this.failuresPerNode = new AtomicIntegerArray(nodesNumber);
        this.resettingNodes = new AtomicBoolean[nodesNumber];
        this.maxFailureNumber = maxFailureNumber;
        this.nodeToIndex = new HashMap<>();

        for (int i = 0; i < nodesNumber; ++i) {
            nodeToIndex.put(clusterUrls.get(i), i);
            resettingNodes[i] = new AtomicBoolean(false);
        }

        this.failuresResetor = new ScheduledThreadPoolExecutor(1);
    }

    public void HandleFailure(String nodeUrl) {
        Integer nodeIndex = nodeToIndex.get(nodeUrl);
        if (failuresPerNode.incrementAndGet(nodeIndex) > maxFailureNumber && !resettingNodes[nodeIndex].get()) {
            resetNodeFailures(nodeUrl);
        }
    }

    public boolean ReadyForRequests(String nodeUrl) {
        return failuresPerNode.get(nodeToIndex.get(nodeUrl)) < maxFailureNumber;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void resetNodeFailures(String nodeUrl) {
        int randomDelay = rnd.nextInt(
                MIN_RESETTING_PERIOD_MILLIS,
                MAX_RESETTING_PERIOD_MILLIS);

        int resetDelay = randomDelay + MIN_RESETTING_DELAY_MILLIS;

        failuresResetor.schedule(
                () -> {
                    Integer nodeIndex = nodeToIndex.get(nodeUrl);
                    failuresPerNode.set(nodeIndex, 0);
                },
                resetDelay,
                TimeUnit.MILLISECONDS
        );
    }

    public void close() {
        Utils.shutdownGracefully(failuresResetor);
    }
}
