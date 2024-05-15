package ru.vk.itmo.test.viktorkorotkikh.dao.compressor;

import java.io.IOException;

public interface Compressor {
    byte[] compress(byte[] src) throws IOException;

    byte[] compress(byte[] src, int len) throws IOException;

    default int calculateLastBlockOffset(int len, byte[] compressedBlock) {
        return len;
    }
}
