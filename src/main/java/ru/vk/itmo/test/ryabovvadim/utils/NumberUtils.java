package ru.vk.itmo.test.ryabovvadim.utils;

import java.util.ArrayList;
import java.util.List;

public final class NumberUtils {

    public static long fromBytes(byte[] bytes) {
        if (bytes.length > 8) {
            throw new IllegalArgumentException("Bytes arrays is too big for long [size: " + bytes.length + "]");
        }

        long result = 0L;
        for (byte value : bytes) {
            result = (result << 8) + (value & 0xff);
        }

        return result;
    }

    public static byte[] toBytes(long value) {
        long val = value;
        List<Byte> bytes = new ArrayList<>();

        while (val > 0) {
            bytes.add((byte) (val & 0xff));
            val >>= 8;
        }

        byte[] result = new byte[bytes.size()];
        for (int i = 0; i < bytes.size(); ++i) {
            result[i] = bytes.get(bytes.size() - i - 1);
        }

        return result;
    }

    public static boolean isInteger(String s) {
        for (int i = 0; i < s.length(); ++i) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }

        return true;
    }
    
    private NumberUtils() {
    }
}
