package ru.vk.itmo.test.pavelemelyanov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashService {
    private final MessageDigest messageDigest;
    private static final Logger logger = LoggerFactory.getLogger(HashService.class);

    public HashService() {
        try {
            this.messageDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            logger.error("HashService init error:", e.getCause());
            throw new IllegalArgumentException("HashService init error:", e);
        }
    }

    public int digest(byte[] bytes) {
        final byte[] hash = messageDigest.digest(bytes);
        return ByteBuffer.wrap(hash).getInt();
    }
}
