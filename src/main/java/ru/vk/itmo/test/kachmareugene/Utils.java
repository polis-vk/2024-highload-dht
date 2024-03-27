package ru.vk.itmo.test.kachmareugene;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class Utils {
    private Utils() {
    }

    public static byte[] longToBytes(long longValue) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte)(longValue & 0xFF);
            longValue >>= 8;
        }
        return result;
    }

    public static void closeExecutorService(ExecutorService exec) {
        if (exec == null) {
            return;
        }

        exec.shutdownNow();
        while (!exec.isTerminated()) {
            try {
                if (exec.awaitTermination(20, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }

            } catch (InterruptedException e) {
                exec.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
