package ru.vk.itmo.test.andreycheshev;

import one.nio.http.Response;

import java.util.Comparator;

import static ru.vk.itmo.test.andreycheshev.RequestHandler.TIMESTAMP_HEADER;

public class GetResponseComparator implements Comparator<Response> {
    @Override
    public int compare(Response first, Response second) {
        long timestamp1 = Long.parseLong(first.getHeader(TIMESTAMP_HEADER));
        long timestamp2 = Long.parseLong(second.getHeader(TIMESTAMP_HEADER));
        if (timestamp1 > timestamp2) {
            return 1;
        } else if (timestamp1 < timestamp2) {
            return -1;
        }
        return 0;
    }
}
