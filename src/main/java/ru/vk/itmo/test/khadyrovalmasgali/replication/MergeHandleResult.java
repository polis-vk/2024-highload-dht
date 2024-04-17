package ru.vk.itmo.test.khadyrovalmasgali.replication;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.khadyrovalmasgali.util.HttpUtil;

import java.net.HttpURLConnection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeHandleResult {

    private static final Logger log = LoggerFactory.getLogger(MergeHandleResult.class);
    private final AtomicInteger successCount;
    private final AtomicInteger count;
    private final int from;
    private final int ack;
    private final HttpSession session;
    private HandleResult mergedResult = new HandleResult(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, Response.EMPTY);

    public MergeHandleResult(int from, int ack, HttpSession session) {
        this.count = new AtomicInteger();
        this.successCount = new AtomicInteger();
        this.ack = ack;
        this.from = from;
        this.session = session;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void add(CompletableFuture<HandleResult> futureHandleResult) {
        futureHandleResult.whenComplete((handleResult, t) -> {
            if (t != null) {
                if (t instanceof Exception) {
                    log.info("Exception in mergeHandleResult", t);
                } else {
                    HttpUtil.sessionSendSafe(session, new Response(Response.INTERNAL_ERROR, Response.EMPTY), log);
                }
            }
            if (validateHandleResult(handleResult)) {
                if (mergedResult.timestamp() <= handleResult.timestamp()) {
                    mergedResult = handleResult;
                }
                int get = successCount.incrementAndGet();
                if (get == ack) {
                    HttpUtil.sessionSendSafe(
                            session,
                            new Response(String.valueOf(mergedResult.status()), mergedResult.data()),
                            log);
                }
            }
            int currentCount = count.incrementAndGet();
            if (currentCount == from && successCount.get() < ack) {
                HttpUtil.sessionSendSafe(session, new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY), log);
            }
        });
    }

    private static boolean validateHandleResult(HandleResult handleResult) {
        if (handleResult == null) {
            return false;
        }
        return handleResult.status() == HttpURLConnection.HTTP_OK
                || handleResult.status() == HttpURLConnection.HTTP_CREATED
                || handleResult.status() == HttpURLConnection.HTTP_ACCEPTED
                || handleResult.status() == HttpURLConnection.HTTP_NOT_FOUND;
    }
}
