package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

public class SStorageEntryDumper extends SStorageDumper<Entry<MemorySegment>> {

    protected SStorageEntryDumper(
            SizeInfo sizeInfo,
            Path storagePath,
            Path indexPath,
            Arena arena
    ) throws IOException {
        super(sizeInfo, storagePath, indexPath, arena);
    }

    @Override
    public void writeEntry(Entry<MemorySegment> entry) {
        final long keyOffset = keysWriter.offset;
        keysWriter.writeMemorySegment(entry.key());

        final long valueOffset;
        final MemorySegment valueSegment = entry.value();
        if (valueSegment == null) {
            valueOffset = -1;
        } else {
            valueOffset = valuesWriter.offset;
            valuesWriter.writeMemorySegment(valueSegment);
        }
        indexDumper.writeEntry(keyOffset, valueOffset);
    }
}
