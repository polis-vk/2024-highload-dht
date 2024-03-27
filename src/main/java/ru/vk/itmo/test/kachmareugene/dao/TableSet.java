package ru.vk.itmo.test.kachmareugene.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
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

    Iterator<EntryWithTimestamp<MemorySegment>> get(
            final MemorySegment from,
            final MemorySegment to) {
        final List<WeightedPeekingEntryIterator> iterators =
                new ArrayList<>(2 + ssTables.size());

        // MemTable goes first
        final Iterator<EntryWithTimestamp<MemorySegment>> memTableIterator =
                memTable.get(from, to);
        if (memTableIterator.hasNext()) {
            iterators.add(
                    new WeightedPeekingEntryIterator(
                            Integer.MIN_VALUE,
                            memTableIterator));
        }

        // Then goes flushing
        if (flushingTable != null) {
            final Iterator<EntryWithTimestamp<MemorySegment>> flushingIterator =
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
            final Iterator<EntryWithTimestamp<MemorySegment>> ssTableIterator =
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
            case 1 -> iterators.get(0);
            default -> new MergingEntryIterator(iterators);
        };
    }

    EntryWithTimestamp<MemorySegment> get(final MemorySegment key) {
        // Slightly optimized version not to pollute the heap

        // First check MemTable
        EntryWithTimestamp<MemorySegment> result = memTable.get(key);
        if (result != null) {
            // Transform tombstone
            return swallowTombstone(result);
        }

        // Then check flushing
        if (flushingTable != null) {
            result = flushingTable.get(key);
            if (result != null) {
                // Transform tombstone
                return swallowTombstone(result);
            }
        }

        // At last check SSTables from freshest to oldest
        for (final SSTable ssTable : ssTables) {
            result = ssTable.get(key);
            if (result != null) {
                // Transform tombstone
                return swallowTombstone(result);
            }
        }

        // Nothing found
        return null;
    }

    // fixme
    private static EntryWithTimestamp<MemorySegment> swallowTombstone(final EntryWithTimestamp<MemorySegment> entry) {
        return  entry;
    }

    EntryWithTimestamp<MemorySegment> upsert(final EntryWithTimestamp<MemorySegment> entry) {
        return memTable.upsert(entry);
    }

    Iterator<EntryWithTimestamp<MemorySegment>> allSSTableEntries() {
        final List<WeightedPeekingEntryIterator> iterators =
                new ArrayList<>(ssTables.size());

        for (int i = 0; i < ssTables.size(); i++) {
            final SSTable ssTable = ssTables.get(i);
            final Iterator<EntryWithTimestamp<MemorySegment>> ssTableIterator =
                    ssTable.get(null, null);
            iterators.add(
                    new WeightedPeekingEntryIterator(
                            i,
                            ssTableIterator));
        }

        return new MergingEntryIterator(iterators);
    }
}
