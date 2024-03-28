package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalevigor.dao.entry.MSegmentTimeEntry;
import ru.vk.itmo.test.kovalevigor.dao.entry.TimeEntry;
import ru.vk.itmo.test.kovalevigor.dao.iterators.ApplyIterator;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.Iterator;

public class SSTimeTableManager extends SSTableManager<TimeEntry<MemorySegment>> {
    private static final ValueLayout.OfLong TIME_LAYOUT = ValueLayout.JAVA_LONG_UNALIGNED;

    public SSTimeTableManager(Path root) throws IOException {
        super(root);
    }

    @Override
    public TimeEntry<MemorySegment> mergeEntries(TimeEntry<MemorySegment> oldEntry, TimeEntry<MemorySegment> newEntry) {
        return oldEntry.timestamp() > newEntry.timestamp() ? oldEntry : newEntry;
    }

    @Override
    public boolean shouldBeNull(TimeEntry<MemorySegment> entry) {
        return entry == null;
    }

    @Override
    protected SStorageDumper<TimeEntry<MemorySegment>> getDumper(
            SizeInfo sizeInfo,
            Path storagePath,
            Path indexPath,
            Arena arena
    ) throws IOException {
        return new SStorageTimeEntryDumper(
                sizeInfo,
                storagePath,
                indexPath,
                arena
        );
    }

    @Override
    protected TimeEntry<MemorySegment> keyValueEntryTo(Entry<MemorySegment> entry) {
        return new MSegmentTimeEntry(
                entry.key(),
                entry.value().asSlice(TIME_LAYOUT.byteSize()),
                entry.value().get(TIME_LAYOUT, 0)
        );
    }

    @Override
    protected Iterator<TimeEntry<MemorySegment>> keyValueEntryTo(Iterator<Entry<MemorySegment>> entry) {
        return new ApplyIterator<>(entry, this::keyValueEntryTo);
    }
}
