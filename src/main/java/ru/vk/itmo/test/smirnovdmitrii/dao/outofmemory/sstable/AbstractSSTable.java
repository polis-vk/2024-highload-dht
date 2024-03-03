package ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

public abstract class AbstractSSTable implements Comparable<AbstractSSTable> {
    protected MemorySegment mapped;
    protected final Path path;
    protected final long priority;

    protected AbstractSSTable(final MemorySegment mapped, final Path path, final long priority) {
        this.mapped = mapped;
        this.path = path;
        this.priority = priority;
    }

    public Path path() {
        return path;
    }

    public long priority() {
        return priority;
    }

    @Override
    public int compareTo(final AbstractSSTable o) {
        final int compareResult = Long.compare(o.priority, this.priority);
        if (compareResult == 0) {
            return this.path.compareTo(o.path);
        }
        return compareResult;
    }

    @Override
    public boolean equals(final Object o) {
        if (o instanceof AbstractSSTable other) {
            return this.compareTo(other) == 0;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(priority);
    }
}
