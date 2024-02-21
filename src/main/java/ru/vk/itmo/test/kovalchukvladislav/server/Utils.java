package ru.vk.itmo.test.kovalchukvladislav.server;

public class Utils {
    public static boolean isBlank(String str) {
        if (str == null) {
            return true;
        }
        int length = str.length();
        if (length == 0) {
            return true;
        }
        for (int i = 0; i < length; i++) {
            if (!Character.isWhitespace(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
