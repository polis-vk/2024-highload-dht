package ru.vk.itmo.test.abramovilya.util;

import one.nio.http.Response;
import one.nio.util.Hash;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.abramovilya.ValueWithTimestamp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Util {
    private Util() {
    }

    public static ValueWithTimestamp byteArrayToObject(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            return (ValueWithTimestamp) objectInputStream.readObject();
        }
    }

    public static byte[] objToByteArray(ValueWithTimestamp object) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(object);
            return byteArrayOutputStream.toByteArray();
        }
    }

    public static List<Integer> getNodesRendezvousSorted(String key, int amount, ServiceConfig config) {
        int clusterSize = config.clusterUrls().size();
        List<Entry<Integer>> numberToHashList = new ArrayList<>();
        for (int i = 0; i < clusterSize; i++) {
            numberToHashList.add(new BaseEntry<>(i, Hash.murmur3(key + i)));
        }
        return numberToHashList.stream()
                .sorted(Comparator.comparing(Entry::value, Comparator.reverseOrder()))
                .map(Entry::key)
                .limit(amount)
                .toList();
    }

    public static int getNodeNumber(String key, ServiceConfig config) {
        int clusterSize = config.clusterUrls().size();
        int maxHash = Integer.MIN_VALUE;
        int argMax = 0;
        for (int i = 0; i < clusterSize; i++) {
            int hash = Hash.murmur3(key + i);
            if (hash > maxHash) {
                maxHash = hash;
                argMax = i;
            }
        }
        return argMax;
    }

    public static long headerTimestampToLong(Response r) {
        String header = r.getHeader("X-TimeStamp: ");
        if (header == null) {
            return Long.MIN_VALUE;
        }
        return Long.parseLong(header);
    }

    public static int quorum(Integer from) {
        return from / 2 + 1;
    }
}
