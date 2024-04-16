package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.async.CustomThreadFactory;
import one.nio.http.HttpSession;
import one.nio.util.Hash;
import org.apache.log4j.Logger;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.shishiginstepan.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DistributedDao {

    private static final String BASE_REQUEST_PATH = "/v0/entity?id=";
    private static final String TIMESTAMP_HEADER = "X-timestamp";
    private static final String INNER_HEADER = "X-inner-request";
    private static final int MULTIPLICATION_FACTOR = 128;
    private final Logger logger = Logger.getLogger("lsm-db-server");
    private final Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> localDao;
    private final String selfUrl;

    private final ExecutorService callbackExecutor = Executors.newFixedThreadPool(
            1,
            new CustomThreadFactory("callback-workers")
    );
    private final ExecutorService remoteExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() / 2,
            new CustomThreadFactory("remote-workers")
    );

    private final SortedMap<Integer, String> nodeRing = new ConcurrentSkipListMap<>();

    private final HttpClient httpClient;
    private int totalNodes;
    private int quorum;

    public DistributedDao(Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> localDao, String selfUrl) {
        this.localDao = localDao;
        this.selfUrl = selfUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(300))
                .executor(Executors.newFixedThreadPool(1))
                .version(HttpClient.Version.HTTP_1_1)
                .executor(remoteExecutor)
                .build();
    }

    public void addNode(String token) {
        for (int i = 0; i < MULTIPLICATION_FACTOR; i++) {
            nodeRing.put(
                    Hash.murmur3((token + i)),
                    token
            );
        }
        totalNodes++;
        quorum = (totalNodes / 2) + 1;
    }

    // Здесь мы будем итерироваться по кольцу и выбирать ноды, причем не просто первые N,
    // а первые N которые памятся на уникальные реальные ноды
    private List<String> selectMultipleNodes(String key, int n) {
        int numberOfNodes = n;
        int keyHash = Hash.murmur3(key);
        List<String> chosenNodes = new ArrayList<>(numberOfNodes);
        SortedMap<Integer, String> ringPart = this.nodeRing.tailMap(keyHash);
        Set<String> tokensOfChosenNodes = new HashSet<>();
        for (String node : ringPart.values()) {
            if (numberOfNodes == 0) {
                break;
            }
            if (tokensOfChosenNodes.add(node)) {
                chosenNodes.add(node);
                numberOfNodes--;
            }
        } // здесь мы можем дойти до конца мапы которая является кольцом нод. если мы до сих пор не набрали нужное
        // кол-во нод, нужно теперь посмотреть другую часть кольца.
        ringPart = this.nodeRing.headMap(keyHash);
        for (String node : ringPart.values()) {
            if (numberOfNodes == 0) {
                break;
            }
            if (tokensOfChosenNodes.add(node)) {
                chosenNodes.add(node);
                numberOfNodes--;
            }
        }

        if (numberOfNodes > 0) {
            throw new NotEnoughUniqueNodes();
        }

        return chosenNodes;
    }

    public EntryWithTimestamp<MemorySegment> getLocal(MemorySegment key) {
        return localDao.get(key);
    }

    public void upsertLocal(EntryWithTimestamp<MemorySegment> entry) {
        localDao.upsert(entry);
    }

    public void close() throws IOException {
        this.localDao.close();
        httpClient.close();
    }

    public void getByQuorum(MemorySegment key, Integer ack, Integer from, HttpSession session) {
        Integer shouldAck = ack == null ? quorum : ack;
        Integer requestFrom = from == null ? totalNodes : from;

        if (shouldAck > totalNodes || requestFrom > totalNodes || shouldAck == 0 || requestFrom == 0) {
            throw new ClusterLimitExceeded();
        }
        List<String> nodesToPoll =
                selectMultipleNodes(
                        new String(key.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8),
                        requestFrom
                );

        var resultHandler = new MergeResultHandler(shouldAck, requestFrom, session);

        for (var node : nodesToPoll) {
            if (node.equals(this.selfUrl)) {
                EntryWithTimestamp<MemorySegment> entry = localDao.get(key);
                ResponseWrapper response;
                if (entry.value() == null) {
                    response = new ResponseWrapper(404, new byte[]{}, entry.timestamp());
                } else {
                    response = new ResponseWrapper(
                            200,
                            entry.value().toArray(ValueLayout.JAVA_BYTE),
                            entry.timestamp());
                }
                resultHandler.add(response);

            } else {
                HttpRequest request = HttpRequest
                        .newBuilder(URI.create(node + BASE_REQUEST_PATH + segmentToString(key)))
                        .GET()
                        .header(INNER_HEADER, "1")
                        .timeout(Duration.ofMillis(500))
                        .build();
                CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray()
                );
                future = future.whenCompleteAsync((r, e) -> {
                            if (e == null) {
                                Long timestamp = Long.parseLong(r.headers().firstValue(TIMESTAMP_HEADER).get());
                                resultHandler.add(new ResponseWrapper(r.statusCode(), r.body(), timestamp));
                            } else {
                                resultHandler.add(new ResponseWrapper(500, new byte[]{}));
                                logger.error(e);
                            }
                        },
                        callbackExecutor);
            }
        }
    }

    private String segmentToString(MemorySegment source) {
        return new String(source.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private static final class NotEnoughUniqueNodes extends RuntimeException {
    }

    public void upsertByQuorum(
            EntryWithTimestamp<MemorySegment> entry,
            Integer ack,
            Integer from,
            HttpSession session
    ) {
        Integer shouldAck = ack == null ? quorum : ack;
        Integer requestFrom = from == null ? totalNodes : from;

        if (shouldAck > requestFrom || requestFrom > totalNodes || shouldAck == 0 || requestFrom == 0) {
            throw new ClusterLimitExceeded();
        }
        List<String> nodesToPoll =
                selectMultipleNodes(
                        new String(entry.key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8),
                        requestFrom
                );

        var resultHandler = new MergeResultHandler(shouldAck, requestFrom, session);
        for (var node : nodesToPoll) {
            if (node.equals(this.selfUrl)) {
                localDao.upsert(entry);
                Integer status = 201;
                if (entry.value() == null) {
                    status = 202;
                }
                resultHandler.add(
                        new ResponseWrapper(status, new byte[]{}, entry.timestamp())
                );

            } else {
                HttpRequest.Builder requestBuilder = request(node, segmentToString(entry.key()))
                        .header(TIMESTAMP_HEADER, String.valueOf(entry.timestamp()));
                HttpRequest request;
                if (entry.value() == null) {
                    request = requestBuilder.DELETE().build();
                } else {
                    request = requestBuilder.PUT(
                                    HttpRequest.BodyPublishers.ofByteArray(entry.value().toArray(ValueLayout.JAVA_BYTE))
                            )
                            .build();
                }
                CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofByteArray()
                );
                future = future.whenCompleteAsync((r, e) -> {
                    if (e == null) {
                        resultHandler.add(new ResponseWrapper(r.statusCode(), new byte[]{}));
                    } else {
                        resultHandler.add(new ResponseWrapper(500, new byte[]{}));
                        logger.error(e);
                    }
                }, callbackExecutor);
            }
        }
    }

    public static final class NoConsensus extends RuntimeException {
    }

    public static final class ClusterLimitExceeded extends RuntimeException {
    }

    private HttpRequest.Builder request(String node, String key) {
        return HttpRequest
                .newBuilder(URI.create(node + BASE_REQUEST_PATH + key))
                .header(INNER_HEADER, "1")
                .timeout(Duration.ofMillis(500));
    }

}
