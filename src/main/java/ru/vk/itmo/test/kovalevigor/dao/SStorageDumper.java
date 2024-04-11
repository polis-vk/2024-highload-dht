package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class SStorageDumper<T extends Entry<MemorySegment>> extends Dumper {

    protected final IndexDumper indexDumper;
    protected final SegmentWriter keysWriter;
    protected final SegmentWriter valuesWriter;

    protected SStorageDumper(
            final SizeInfo sizeInfo,
            final Path storagePath,
            final Path indexPath,
            final Arena arena
    ) throws IOException {
        super(storagePath, getSize(sizeInfo.keysSize, sizeInfo.valuesSize), arena);

        keysWriter = new SegmentWriter(
                Files.createTempFile(null, null),
                sizeInfo.keysSize,
                arena
        );
        try {
            valuesWriter = new SegmentWriter(
                    Files.createTempFile(null, null),
                    sizeInfo.valuesSize,
                    arena
            );
        } catch (IOException e) {
            Files.deleteIfExists(keysWriter.path);
            throw e;
        }
        try {
            indexDumper = new IndexDumper(sizeInfo.size, indexPath, arena);
        } catch (IOException e) {
            deleteSupportFiles();
            throw e;
        }

        indexDumper.setKeysSize(sizeInfo.keysSize);
        indexDumper.setValuesSize(sizeInfo.valuesSize);
    }

    public static long getSize(final long keysSize, final long valuesSize) {
        return keysSize + valuesSize;
    }

    public static long getIndexSize(final long entryCount) {
        return IndexDumper.getSize(entryCount);
    }

    public void setKeysSize(final long keysSize) {
        indexDumper.setKeysSize(keysSize);
    }

    public void setValuesSize(final long valuesSize) {
        indexDumper.setValuesSize(valuesSize);
    }

    @Override
    protected void writeHead() {
        indexDumper.writeHead();
    }

    public abstract void writeEntry(final T entry);

    private void deleteSupportFiles() throws IOException {
        try {
            Files.deleteIfExists(keysWriter.path);
        } finally {
            Files.deleteIfExists(valuesWriter.path);
        }
    }

    @Override
    public void close() throws IOException {
        writeHead();
        offset = writeMemorySegment(keysWriter.memorySegment, offset, indexDumper.keysSize);
        offset = writeMemorySegment(valuesWriter.memorySegment, offset, indexDumper.valuesSize);

        indexDumper.close();

        deleteSupportFiles();
    }
}
