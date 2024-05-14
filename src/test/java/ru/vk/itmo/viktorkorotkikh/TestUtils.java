package ru.vk.itmo.viktorkorotkikh;

import org.junit.jupiter.api.Assertions;
import org.opentest4j.AssertionFailedError;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public final class TestUtils {
    private TestUtils() {
    }

    public static MemorySegment memorySegmentFromString(String data) {
        return data == null ? null : MemorySegment.ofArray(data.getBytes(StandardCharsets.UTF_8));
    }

    public static String memorySegmentToString(MemorySegment memorySegment) {
        if (memorySegment == null) {
            return null;
        }
        return new String(memorySegment.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    public static TimestampedEntry<MemorySegment> fromStringToMemorySegmentEntry(
            TimestampedEntry<String> stringTimestampedEntry
    ) {
        return new TimestampedEntry<>(
                memorySegmentFromString(stringTimestampedEntry.key()),
                memorySegmentFromString(stringTimestampedEntry.value()),
                stringTimestampedEntry.timestamp()
        );
    }

    public static TimestampedEntry<String> fromMemorySegmentToStringEntry(
            TimestampedEntry<MemorySegment> memorySegmentTimestampedEntry
    ) {
        return new TimestampedEntry<>(
                memorySegmentToString(memorySegmentTimestampedEntry.key()),
                memorySegmentToString(memorySegmentTimestampedEntry.value()),
                memorySegmentTimestampedEntry.timestamp()
        );
    }

    public static List<TimestampedEntry<String>> bigValues(int count, int valueSize) {
        char[] data = new char[valueSize / 2];
        Arrays.fill(data, 'V');
        return entries("k", new String(data), count);
    }

    public static TimestampedEntry<String> entryAt(int index) {
        return new TimestampedEntry<>(keyAt(index), valueAt(index), System.currentTimeMillis());
    }

    public static TimestampedEntry<String> removeEntryAt(int index) {
        return new TimestampedEntry<>(stringAt("key", index), null, System.currentTimeMillis());
    }

    public static String keyAt(int index) {
        return stringAt("key", index);
    }

    public static String valueAt(int index) {
        return stringAt("value", index);
    }

    private static String stringAt(String prefix, int index) {
        String paddedIdx = String.format("%010d", index);
        return prefix + paddedIdx;
    }

    public static List<TimestampedEntry<String>> entries(String keyPrefix, String valuePrefix, int count) {
        long baseTimestamp = System.currentTimeMillis();
        return new AbstractList<>() {
            @Override
            public TimestampedEntry<String> get(int index) {
                if (Thread.interrupted()) {
                    throw new RuntimeException(new InterruptedException());
                }
                if (index >= count || index < 0) {
                    throw new IndexOutOfBoundsException("Index is " + index + ", size is " + count);
                }
                String paddedIdx = String.format("%010d", index);
                return new TimestampedEntry<>(keyPrefix + paddedIdx, valuePrefix + paddedIdx, baseTimestamp + index);
            }

            @Override
            public int size() {
                return count;
            }
        };
    }

    public static void assertSame(
            Iterator<? extends TimestampedEntry<MemorySegment>> iterator,
            List<? extends TimestampedEntry<String>> expected
    ) {
        int index = 0;
        for (TimestampedEntry<String> entry : expected) {
            if (!iterator.hasNext()) {
                throw new AssertionFailedError("No more entries in iterator: " + index + " from " + expected.size() + " entries iterated");
            }
            int finalIndex = index;
            TimestampedEntry<String> actual = fromMemorySegmentToStringEntry(iterator.next());
            Assertions.assertEquals(entry, actual, () -> "wrong entry at index " + finalIndex + " from " + expected.size());
            index++;
        }
        if (iterator.hasNext()) {
            throw new AssertionFailedError("Unexpected entry at index " + index + " from " + expected.size() + " elements: " + iterator.next());
        }
    }
}
