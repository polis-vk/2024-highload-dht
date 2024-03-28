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

    public static byte[] addLongToArray(long number, byte[] given) {
        int givenLength = 0;
        if (given != null) {
            givenLength = given.length;
        }
        byte[] result = new byte[Long.BYTES + givenLength];
        for (int i = 0; i < Long.BYTES; ++i) {
            result[i] = (byte)(number & 0xFF);
            number >>= Byte.SIZE;
        }

        if (given == null) {
            result[Long.BYTES - 1] = (byte)(result[Long.BYTES - 1] | 0x80);
        } else {
            System.arraycopy(given, 0, result, 8, given.length);
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
