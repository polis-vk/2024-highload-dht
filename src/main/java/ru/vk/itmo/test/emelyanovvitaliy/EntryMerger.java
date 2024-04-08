package ru.vk.itmo.test.emelyanovvitaliy;

import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class EntryMerger implements Merger<TimestampedEntry<MemorySegment>> {
    private final int from;
    private final int ack;
    private final AtomicReference<TimestampedEntry<MemorySegment>> lastEntry;
    private final AtomicInteger foundSmthCnt;
    private final AtomicInteger notFoundCnt;
    private final CompletableFuture<TimestampedEntry<MemorySegment>> futureToComplete;

    public EntryMerger(int from, int ack) {
        this.from = from;
        this.ack = ack;
        lastEntry = new AtomicReference<>();
        foundSmthCnt = new AtomicInteger(0);
        notFoundCnt = new AtomicInteger(0);
        futureToComplete = new CompletableFuture<>();
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
        return a.timestamp() > b.timestamp() ? a : b;
    }

    @Override
    public void acceptResult(TimestampedEntry<MemorySegment> entry, Throwable throwable) {
        if (entry == null) {
            if (notFoundCnt.incrementAndGet() == from - ack + 1) {
                futureToComplete.complete(null);
            }
        } else {
            TimestampedEntry<MemorySegment> current;
            TimestampedEntry<MemorySegment> lastOf2;
            do {
                current = lastEntry.get();
                lastOf2 = findLastEntry(current, entry);
                if (Objects.equals(lastOf2, current)) { // if ours variant is older
                    break;
                }
                // try again if lastEntry updated
            } while (lastEntry.compareAndExchange(current, lastOf2) != current);
            if (foundSmthCnt.incrementAndGet() == ack) {
                futureToComplete.complete(lastEntry.get());
            }
        }
    }

    @Override
    public CompletableFuture<TimestampedEntry<MemorySegment>> getCompletableFuture() {
        return futureToComplete;
    }
}
