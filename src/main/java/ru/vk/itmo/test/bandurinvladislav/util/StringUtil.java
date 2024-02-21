package ru.vk.itmo.test.bandurinvladislav.util;

public class StringUtil {
    private StringUtil() {
        throw new UnsupportedOperationException();
    }

    public static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
