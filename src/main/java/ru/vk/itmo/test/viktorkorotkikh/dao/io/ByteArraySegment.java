package ru.vk.itmo.test.viktorkorotkikh.dao.io;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Growable buffer with {@link ByteBuffer} and {@link MemorySegment} interface.
 *
 * @author incubos
 */
public final class ByteArraySegment {
    private byte[] array;
    private final boolean resizable;
    private MemorySegment segment;

    public ByteArraySegment(final int capacity) {
        this(capacity, true);
    }

    public ByteArraySegment(final int capacity, final boolean resizable) {
        this.array = new byte[capacity];
        this.resizable = resizable;
        this.segment = MemorySegment.ofArray(array);
    }

    public void withArray(final ArrayConsumer consumer) throws IOException {
        consumer.process(array);
    }

    public <T> T withArrayReturn(final ArrayProcessor<T> consumer) throws IOException {
        return consumer.process(array);
    }

    public MemorySegment segment() {
        return segment;
    }

    public void ensureCapacity(final long size) {
        if (!resizable) {
            throw new IllegalStateException("ByteArraySegment is not resizable");
        }
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too big!");
        }

        final int capacity = (int) size;
        if (array.length >= capacity) {
            return;
        }

        // Grow to the nearest bigger power of 2
        final int newSize = Integer.highestOneBit(capacity) << 1;
        array = new byte[newSize];
        segment = MemorySegment.ofArray(array);
    }

    public interface ArrayConsumer {
        void process(byte[] array) throws IOException;
    }

    public interface ArrayProcessor<T> {
        T process(byte[] array) throws IOException;
    }
}
