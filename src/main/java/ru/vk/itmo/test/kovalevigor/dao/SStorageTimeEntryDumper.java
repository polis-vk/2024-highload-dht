package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.test.kovalevigor.dao.entry.TimeEntry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

public class SStorageTimeEntryDumper extends SStorageDumper<TimeEntry<MemorySegment>> {

    protected SStorageTimeEntryDumper(
            SizeInfo sizeInfo,
            Path storagePath,
            Path indexPath,
            Arena arena
    ) throws IOException {
        super(sizeInfo, storagePath, indexPath, arena);
    }

    @Override
    public void writeEntry(TimeEntry<MemorySegment> entry) {
        final long keyOffset = keysWriter.offset;
        keysWriter.writeMemorySegment(entry.key());

        final long valueOffset = valuesWriter.offset;
        valuesWriter.writeLong(entry.timestamp());
        final MemorySegment valueSegment = entry.value();
        if (valueSegment != null) {
            valuesWriter.writeMemorySegment(valueSegment);
        }
        indexDumper.writeEntry(keyOffset, valueOffset);
    }
}
