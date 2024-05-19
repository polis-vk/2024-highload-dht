package ru.vk.itmo.test.tuzikovalexandr;

import java.util.Comparator;
import java.util.List;

public final class Utils {

    private Utils() {
    }

    public static void sortResponses(List<ResponseWithUrl> successResponses) {
        successResponses.sort(Comparator.comparingLong(r -> {
            String timestamp = r.getResponse().getHeader(Constants.NIO_TIMESTAMP_HEADER);
            return timestamp == null ? 0 : Long.parseLong(timestamp);
        }));
    }
}
