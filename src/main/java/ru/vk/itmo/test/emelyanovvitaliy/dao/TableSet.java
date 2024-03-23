package ru.vk.itmo.test.emelyanovvitaliy.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.sql.Time;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Data set in various tables.
 *
 * @author incubos
 */
final class TableSet {
    final MemTable memTable;
    final AtomicLong memTableSize;
    // null or read-only
    final MemTable flushingTable;
    // From freshest to oldest
    final List<SSTable> ssTables;

    private TableSet(
            final MemTable memTable,
            final AtomicLong memTableSize,
            final MemTable flushingTable,
            final List<SSTable> ssTables) {
        this.memTable = memTable;
        this.memTableSize = memTableSize;
        this.flushingTable = flushingTable;
        this.ssTables = ssTables;
    }

    static TableSet from(final List<SSTable> ssTables) {
        return new TableSet(
                new MemTable(),
                new AtomicLong(),
                null,
                ssTables);
    }

    int nextSequence() {
        return ssTables.stream()
                .mapToInt(t -> t.sequence)
                .max()
                .orElse(0) + 1;
    }

    TableSet flushing() {
        if (memTable.isEmpty()) {
            throw new IllegalStateException("Nothing to flush");
        }

        if (flushingTable != null) {
            throw new IllegalStateException("Already flushing");
        }

        return new TableSet(
                new MemTable(),
                new AtomicLong(),
                memTable,
                ssTables);
    }

    TableSet flushed(final SSTable flushed) {
        final List<SSTable> newSSTables = new ArrayList<>(ssTables.size() + 1);
        newSSTables.add(flushed);
        newSSTables.addAll(ssTables);
        return new TableSet(
                memTable,
                memTableSize,
                null,
                newSSTables);
    }

    TableSet compacted(
            final Set<SSTable> replaced,
            final SSTable with) {
        final List<SSTable> newSsTables = new ArrayList<>(this.ssTables.size() + 1);

        // Keep not replaced SSTables
        for (final SSTable ssTable : this.ssTables) {
            if (!replaced.contains(ssTable)) {
                newSsTables.add(ssTable);
            }
        }

        // Logically the oldest one
        newSsTables.add(with);

        return new TableSet(
                memTable,
                memTableSize,
                flushingTable,
                newSsTables);
    }

    Iterator<TimestampedEntry<MemorySegment>> get(
            final MemorySegment from,
            final MemorySegment to) {
        final List<WeightedPeekingEntryIterator> iterators =
                new ArrayList<>(2 + ssTables.size());

        // MemTable goes first
        final Iterator<TimestampedEntry<MemorySegment>> memTableIterator =
                memTable.get(from, to);
        if (memTableIterator.hasNext()) {
            iterators.add(
                    new WeightedPeekingEntryIterator(
                            Integer.MIN_VALUE,
                            memTableIterator));
        }

        // Then goes flushing
        if (flushingTable != null) {
            final Iterator<TimestampedEntry<MemorySegment>> flushingIterator =
                    flushingTable.get(from, to);
            if (flushingIterator.hasNext()) {
                iterators.add(
                        new WeightedPeekingEntryIterator(
                                Integer.MIN_VALUE + 1,
                                flushingIterator));
            }
        }

        // Then go all the SSTables
        for (int i = 0; i < ssTables.size(); i++) {
            final SSTable ssTable = ssTables.get(i);
            final Iterator<TimestampedEntry<MemorySegment>> ssTableIterator =
                    ssTable.get(from, to);
            if (ssTableIterator.hasNext()) {
                iterators.add(
                        new WeightedPeekingEntryIterator(
                                i,
                                ssTableIterator));
            }
        }

        return switch (iterators.size()) {
            case 0 -> Collections.emptyIterator();
            case 1 -> iterators.getFirst();
            default -> new MergingEntryIterator(iterators);
        };
    }

    TimestampedEntry<MemorySegment> get(final MemorySegment key) {
        // Slightly optimized version not to pollute the heap

        // First check MemTable
        TimestampedEntry<MemorySegment> result = memTable.get(key);
        if (result != null) {
            return result;
        }

        // Then check flushing
        if (flushingTable != null) {
            result = flushingTable.get(key);
            if (result != null) {
                return result;
            }
        }

        // At last check SSTables from freshest to oldest
        for (final SSTable ssTable : ssTables) {
            result = ssTable.get(key);
            if (result != null) {
                // Transform tombstone
                return result;
            }
        }

        // Nothing found
        return null;
    }

    TimestampedEntry<MemorySegment> upsert(final TimestampedEntry<MemorySegment> entry) {
        return memTable.upsert(entry);
    }

    Iterator<TimestampedEntry<MemorySegment>> allSSTableEntries() {
        final List<WeightedPeekingEntryIterator> iterators =
                new ArrayList<>(ssTables.size());

        for (int i = 0; i < ssTables.size(); i++) {
            final SSTable ssTable = ssTables.get(i);
            final Iterator<TimestampedEntry<MemorySegment>> ssTableIterator =
                    ssTable.get(null, null);
            iterators.add(
                    new WeightedPeekingEntryIterator(
                            i,
                            ssTableIterator));
        }

        return new MergingEntryIterator(iterators);
    }
}
