package ru.vk.itmo.test.smirnovdmitrii.server;

import java.time.Instant;

public final class Utils {
    public static final String TIMESTAMP_HEADER_NAME = "X-KEY-TS";
    public static final String REDIRECT_HEADER_NAME = "X-REPLICATION-REQUEST";
    public static final String REDIRECT_ONE_NIO_HEADER_NAME = REDIRECT_HEADER_NAME + ":";

    private Utils() {
    }

    public static long currentMillis() {
        return Instant.now().toEpochMilli();
    }
}
