package ru.vk.itmo.test.khadyrovalmasgali.hashing;

import one.nio.util.Hash;

public class RendezvousHash {
    public static int hashToUnitInterval(String key) {
        return Hash.murmur3(key);
    }
}
