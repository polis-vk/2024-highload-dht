package ru.vk.itmo.test.kovalchukvladislav.server;

public final class Utils {
    private Utils() {
    }

    public static boolean isEmpty(String str) {
        if (str == null) {
            return true;
        }
        int length = str.length();
        return length == 0;
    }
}
