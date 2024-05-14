package ru.vk.itmo.test.viktorkorotkikh.dao.decompressor;

import java.io.IOException;

public interface Decompressor {

    void decompress(byte[] src, byte[] dest, int destOff, int uncompressedSize, int compressedSize) throws IOException;
}
