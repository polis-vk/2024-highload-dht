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
//        Iterator<TimeEntry<MemorySegment>> a = get(
//                MemorySegment.ofArray("k0".getBytes(StandardCharsets.UTF_8)),
//                MemorySegment.ofArray("k2".getBytes(StandardCharsets.UTF_8)));
//        int test = 0;
//        while (a.hasNext()) {
//            a.next();
//            System.out.println(new String(a.next().key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
//            test += 1;
//            if (test > 1000) {
//                break;
//            }
//            MemorySegment b = a.next().key();
//            byte[] bytes = b.toArray(ValueLayout.JAVA_BYTE);
////            String kek = new String(bytes, StandardCharsets.UTF_8);
//            System.out.println(bytes.length);
//            b.unload();

//            System.out.flush();
//            try {
////                System.out.println(a.next().key().byteSize());
////                System.out.println(a.next().timestamp());
//                System.out.println(new String(a.next().key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
//            } catch (Exception ignored){}
//        }
//        System.out.println(test);
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
        if (entry == null) {
            return null;
        }
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
