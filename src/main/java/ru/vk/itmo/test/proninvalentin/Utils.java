package ru.vk.itmo.test.proninvalentin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class Utils {
    private static final int SOFT_SHUT_DOWN_TIME = 20;
    private static final int HARD_SHUT_DOWN_TIME = 10;

    private Utils() {
    }

    public static void shutdownGracefully(ExecutorService pool) {
        pool.shutdown();
        try {
            pool.awaitTermination(SOFT_SHUT_DOWN_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        pool.shutdownNow();
        try {
            pool.awaitTermination(HARD_SHUT_DOWN_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
