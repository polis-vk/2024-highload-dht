package ru.vk.itmo.test.pelogeikomakar;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class Convertor {

    private Convertor() {
        throw new UnsupportedOperationException("cannot be instantiated");
    }

    public static byte[] addLongToArray(long l, byte[] given) {
        int gLength = 0;
        if (given != null) {
            gLength = given.length;
        }
        byte[] result = new byte[Long.BYTES + gLength];
        for (int i = 0; i < Long.BYTES; ++i) {
            result[i] = (byte)(l & 0xFF);
            l >>= Byte.SIZE;
        }

        if (given != null) {
            System.arraycopy(given, 0, result, 8, given.length);
        } else {
            result[Long.BYTES - 1] = (byte)(result[Long.BYTES - 1] | 0x80);
        }

        return result;
    }

    public static long getTimeStamp(MemorySegment segment) {
        long time = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);

        if (((time >> (Byte.SIZE * 7)) & 0x80) != 0) {
            time &= ~(1L << 63);
        }
        return time;
    }

    public static boolean isValNull(MemorySegment segment) {
        long time = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
        return ((time >> (Byte.SIZE * 7)) & 0x80) != 0;
    }

    public static MemorySegment stringToMemorySegment(String str) {
        return MemorySegment.ofArray(str.getBytes(StandardCharsets.UTF_8));
    }

    public static Entry<MemorySegment> requestToEntry(String key, byte[] value, long timeStamp) {
        byte[] storedValue = addLongToArray(timeStamp, value);
        return new BaseEntry<>(stringToMemorySegment(key), MemorySegment.ofArray(storedValue));
    }

    public static byte[] getValueNotNullAsBytes(MemorySegment segment) {
        return segment.asSlice(Long.BYTES).toArray(ValueLayout.JAVA_BYTE);
    }

}