package ru.vk.itmo.test.tarazanovmaxim.hash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Hasher {
    private final ThreadLocal<MessageDigest> messageDigest = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            logger.error("Error initializing Hasher: ", e.getMessage());
            throw new IllegalArgumentException("Error initializing MessageDigest", e);
        }
    });
    private static final Logger logger = LoggerFactory.getLogger(Hasher.class);

    public int digest(byte[] bytes) {
        final byte[] hash = messageDigest.get().digest(bytes);
        int value = 0;
        for (byte byte_ : hash) {
            value = (value << 1) + (byte_ & 255);
        }
        return value;
    }

    public void close() {
        messageDigest.remove();
    }
}
