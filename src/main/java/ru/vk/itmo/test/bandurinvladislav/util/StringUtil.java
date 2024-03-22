package ru.vk.itmo.test.bandurinvladislav.util;

public final class StringUtil {
    private StringUtil() {
        throw new UnsupportedOperationException();
    }

    public static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }
}
