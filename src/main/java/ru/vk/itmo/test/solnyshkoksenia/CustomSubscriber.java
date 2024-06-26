package ru.vk.itmo.test.solnyshkoksenia;

import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;

public class CustomSubscriber implements BodySubscriber<byte[]> {
    private static final byte DELIMITER = '\n';
    volatile CompletableFuture<byte[]> bodyCF;
    Flow.Subscription subscription;
    List<ByteBuffer> responseData = new CopyOnWriteArrayList<>();

    @Override
    public CompletionStage<byte[]> getBody() {
        while (bodyCF == null) {
            Thread.onSpinWait();
        }
        return bodyCF;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        buffers.forEach(ByteBuffer::rewind);
        responseData.addAll(buffers);
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        bodyCF = CompletableFuture.failedFuture(throwable);
    }

    @Override
    public void onComplete() {
        bodyCF = CompletableFuture.completedFuture(toBytes(responseData));
    }

    private List<byte[]> toArrays(List<ByteBuffer> buffers) {
        List<byte[]> chunks = new ArrayList<>();
        for (ByteBuffer buffer : buffers) {
            int remaining = buffer.remaining();
            byte[] cur = new byte[remaining];
            buffer.get(cur, 0, remaining);
            chunks.add(cur);
        }
        return chunks;
    }

    private boolean containsDelim(byte[] bytes) {
        for (int element : bytes) {
            if (element == DELIMITER) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithDelim(byte[] bytes) {
        return DELIMITER == bytes[0];
    }

    private byte[] merge(byte[] src1, byte[] src2) {
        byte[] dst = new byte[src1.length + src2.length];
        System.arraycopy(src1, 0, dst, 0, src1.length);
        System.arraycopy(src2, 0, dst, src1.length, src2.length);
        return dst;
    }

    private byte[] toArray(List<byte[]> bytes) {
        var size = bytes.stream().mapToInt(b -> b.length).sum() + bytes.size();
        byte[] dst = new byte[size];
        int offset = 0;
        for (byte[] src : bytes) {
            System.arraycopy(src, 0, dst, offset, src.length);
            offset += src.length;
            dst[offset] = DELIMITER;
            offset++;
        }
        return dst;
    }

    private byte[] toBytes(List<ByteBuffer> buffers) {
        List<byte[]> chunks = toArrays(buffers);
        List<byte[]> bytes = new ArrayList<>();

        boolean predEndsWithDelim = false;
        boolean predContainsDelim = true;

        byte[] pred = null;
        for (int i = 0; i < chunks.size(); i++) {
            byte[] cur = chunks.get(i);
            byte[] next = i + 1 == chunks.size() ? new byte[0] : chunks.get(i + 1);
            if (startsWithDelim(cur) && !predContainsDelim) {
                cur = merge(pred, cur);
            } else if (!containsDelim(cur) && predEndsWithDelim) {
                if (startsWithDelim(next)) {
                    bytes.add(pred);
                } else {
                    cur = merge(pred, cur);
                }
            } else if (pred != null) {
                bytes.add(pred);
            }

            predEndsWithDelim = DELIMITER == cur[cur.length - 1];
            predContainsDelim = containsDelim(cur);
            pred = cur;
        }

        if (pred != null) {
            bytes.add(pred);
        }
        return toArray(bytes);
    }
}
