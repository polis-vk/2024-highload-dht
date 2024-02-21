package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.Request;

import java.util.Set;

public final class Settings {
    public static final long FLUSH_THRESHOLD_BYTES = 8 * 1024 * 1024; // 1мб
    public static final String VERSION_PREFIX = "/v0";
    public static final Set<Integer> SUPPORTED_METHODS = Set.of(
            Request.METHOD_GET,
            Request.METHOD_PUT,
            Request.METHOD_DELETE
    );

    private Settings() {
    }
}
