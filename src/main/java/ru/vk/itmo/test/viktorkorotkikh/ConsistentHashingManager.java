package ru.vk.itmo.test.viktorkorotkikh;

import ru.vk.itmo.test.viktorkorotkikh.exception.NotSupportedAlgorithmException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashingManager {
    private final NavigableMap<Integer, String> hashRing;

    public ConsistentHashingManager(int numberOfVNodes, List<String> clusterUrls) {
        hashRing = new TreeMap<>();
        MessageDigest messageDigestInstance = getMessageDigestInstance();
        for (String clusterUrl : clusterUrls) {
            for (int j = 0; j < numberOfVNodes; j++) {
                final byte[] input = (clusterUrl + j).getBytes(StandardCharsets.UTF_8);
                final byte[] hash = messageDigestInstance.digest(input);
                hashRing.put(hashToInt(hash), clusterUrl);
            }
        }
    }

    public String getServerByKey(final byte[] key) {
        final int keyHash = hashToInt(getMessageDigestInstance().digest(key));
        SortedMap<Integer, String> tailMap = hashRing.tailMap(keyHash);
        return (tailMap.isEmpty() ? hashRing.firstEntry() : tailMap.firstEntry()).getValue();
    }

    private static MessageDigest getMessageDigestInstance() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new NotSupportedAlgorithmException("SHA-256 not supported", e);
        }
    }

    private static int hashToInt(byte[] bytes) {
        return bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }
}
