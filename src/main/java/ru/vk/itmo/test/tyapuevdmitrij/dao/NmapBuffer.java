package ru.vk.itmo.test.tyapuevdmitrij.dao;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;

public final class NmapBuffer {

    private NmapBuffer() {
        throw new IllegalStateException("Utility class");
    }

    static MemorySegment getReadBufferFromSsTable(Path ssTablePath, Arena readArena) {
        MemorySegment buffer;
        boolean created = false;
        try (FileChannel channel = FileChannel.open(ssTablePath, StandardOpenOption.READ)) {
            buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(ssTablePath), readArena);
            created = true;
        } catch (IOException e) {
            buffer = null;
        } finally {
            if (!created) {
                readArena.close();
            }
        }
        return buffer;
    }

    static MemorySegment getWriteBufferToSsTable(Long writeBytes,
                                                 Path path,
                                                 Arena writeArena
    ) throws IOException {
        MemorySegment buffer;
        try (
             FileChannel channel = FileChannel.open(path, EnumSet.of(StandardOpenOption.READ,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0,
                    writeBytes, writeArena);
        }
        return buffer;
    }
}


