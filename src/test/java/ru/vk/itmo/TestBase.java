package ru.vk.itmo;

import org.junit.jupiter.api.Assertions;

import java.net.http.HttpClient;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Contains utility methods for unit tests.
 *
 * @author Vadim Tsesko
 */
public abstract class TestBase {

    private static final int VALUE_LENGTH = 1024;

    protected final HttpClient client = HttpClient.newHttpClient();

    protected static String randomId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private static byte[] randomBytes(final int length) {
        Assertions.assertTrue(length > 0);
        final byte[] result = new byte[length];
        ThreadLocalRandom.current().nextBytes(result);
        return result;
    }

    protected static byte[] randomValue() {
        return randomBytes(VALUE_LENGTH);
    }
}
