package ru.vk.itmo.test.kovalevigor.server.strategy.util;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ru.vk.itmo.test.kovalevigor.server.strategy.util.ServerUtil.CHARSET;

public class ChunkQueueItem extends JoinedQueueItem {
    public static final MemorySegment CHUNK_LINE_END = MemorySegment.ofArray("\r\n".getBytes(CHARSET));
    public static final MemorySegment KEY_VALUE_SEP = MemorySegment.ofArray("\n".getBytes(CHARSET));
    private final List<MemorySegment> chunk;
    public ChunkQueueItem(int bufferSize) {
        super(bufferSize);
        chunk = new ArrayList<>(
                Arrays.asList(
                        null,
                        CHUNK_LINE_END,
                        null,
                        KEY_VALUE_SEP,
                        null,
                        CHUNK_LINE_END
                )
        );
    }

    public void setChunk(Entry<MemorySegment> entry) {
        long keySize = entry.key().byteSize();
        long valueSize = entry.value() == null ? 0 : entry.value().byteSize();
        long totalSize = keySize + valueSize + KEY_VALUE_SEP.byteSize();
        chunk.set(0, mapToHex(totalSize));
        chunk.set(2, entry.key());
        chunk.set(4, entry.value());
        setItems(chunk.iterator());
    }

    private static MemorySegment mapToHex(long value) {
        return MemorySegment.ofArray(
                Long.toHexString(value).getBytes(CHARSET)
        );
    }
}
