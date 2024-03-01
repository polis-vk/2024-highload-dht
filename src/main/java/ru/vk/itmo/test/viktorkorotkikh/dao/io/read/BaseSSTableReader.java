package ru.vk.itmo.test.viktorkorotkikh.dao.io.read;

import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.LSMPointerIterator;
import ru.vk.itmo.test.viktorkorotkikh.dao.MemorySegmentComparator;
import ru.vk.itmo.test.viktorkorotkikh.dao.Utils;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.NoSuchElementException;

/**
 * SSTable reader without compression.
 *
 * @author vitekkor
 */
public class BaseSSTableReader extends AbstractSSTableReader {
    private static final long METADATA_SIZE = Long.BYTES + 1L;

    private static final long ENTRY_METADATA_SIZE = Long.BYTES;

    public BaseSSTableReader(
            MemorySegment mappedSSTable,
            MemorySegment mappedIndexFile,
            int index
    ) {
        super(mappedSSTable, mappedIndexFile, index);
    }

    private long getMinKeySizeOffset() {
        return mappedIndexFile.get(ValueLayout.JAVA_LONG_UNALIGNED, METADATA_SIZE);
    }

    private long getMaxKeySizeOffset() {
        long entriesSize = getEntriesSize();
        return mappedIndexFile.get(
                ValueLayout.JAVA_LONG_UNALIGNED,
                METADATA_SIZE + (entriesSize - 1) * ENTRY_METADATA_SIZE
        );
    }

    @Override
    public LSMPointerIterator iterator(MemorySegment from, MemorySegment to) {
        long fromPosition = getMinKeySizeOffset();
        long toPosition = getMaxKeySizeOffset();
        if (from != null) {
            fromPosition = getEntryOffset(from, SearchOption.GTE);
            if (fromPosition == -1) {
                return new LSMPointerIterator.Empty(index);
            }
        }
        if (to != null) {
            toPosition = getEntryOffset(to, SearchOption.LT);
            if (toPosition == -1) {
                return new LSMPointerIterator.Empty(index);
            }
        }

        return new BaseSSTableIterator(fromPosition, toPosition);
    }

    @Override
    protected Entry<MemorySegment> getByIndex(long index) {
        long keySize = mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, index);
        MemorySegment savedKey = mappedSSTable.asSlice(index + Long.BYTES, keySize);

        long valueOffset = index + Long.BYTES + keySize;
        long valueSize = mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, valueOffset);
        if (valueSize == -1) {
            return new BaseEntry<>(savedKey, null);
        }
        return new BaseEntry<>(savedKey, mappedSSTable.asSlice(valueOffset + Long.BYTES, valueSize));
    }

    private long getEntriesSize() {
        return mappedIndexFile.get(ValueLayout.JAVA_LONG_UNALIGNED, 1);
    }

    @Override
    protected long getEntryOffset(MemorySegment key, SearchOption searchOption) {
        // binary search
        long entriesSize = getEntriesSize();
        long left = 0;
        long right = entriesSize - 1;
        while (left <= right) {
            long mid = (right + left) / 2;
            long keySizeOffset = mappedIndexFile.get(
                    ValueLayout.JAVA_LONG_UNALIGNED,
                    METADATA_SIZE + mid * ENTRY_METADATA_SIZE
            );
            long keySize = mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, keySizeOffset);
            long keyOffset = keySizeOffset + Long.BYTES;
            int keyComparison = MemorySegmentComparator.INSTANCE.compare(
                    mappedSSTable, keyOffset,
                    keyOffset + keySize,
                    key
            );
            if (keyComparison < 0) {
                left = mid + 1;
            } else if (keyComparison > 0) {
                right = mid - 1;
            } else {
                return switch (searchOption) {
                    case EQ, GTE -> keySizeOffset;
                    case LT -> keySizeOffset - METADATA_SIZE;
                };
            }
        }

        return switch (searchOption) {
            case EQ -> -1;
            case GTE -> {
                if (left == entriesSize) {
                    yield -1;
                } else {
                    yield mappedIndexFile.get(
                            ValueLayout.JAVA_LONG_UNALIGNED,
                            METADATA_SIZE + left * ENTRY_METADATA_SIZE
                    );
                }
            }
            case LT -> {
                if (right == -1) {
                    yield -1;
                } else {
                    yield mappedIndexFile.get(
                            ValueLayout.JAVA_LONG_UNALIGNED,
                            METADATA_SIZE + right * ENTRY_METADATA_SIZE
                    );
                }
            }
        };
    }

    public final class BaseSSTableIterator extends LSMPointerIterator {
        private long fromPosition;
        private final long toPosition;

        private BaseSSTableIterator(long fromPosition, long toPosition) {
            this.fromPosition = fromPosition;
            this.toPosition = toPosition;
        }

        @Override
        public int getPriority() {
            return index;
        }

        @Override
        public MemorySegment getPointerKeySrc() {
            return mappedSSTable;
        }

        @Override
        public long getPointerKeySrcOffset() {
            return fromPosition + Long.BYTES;
        }

        @Override
        public boolean isPointerOnTombstone() {
            long keySize = mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, fromPosition);
            long valueOffset = fromPosition + Long.BYTES + keySize;
            long valueSize = mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, valueOffset);
            return valueSize == -1;
        }

        @Override
        public void shift() {
            fromPosition += getPointerSize();
        }

        @Override
        public long getPointerSize() {
            long keySize = mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, fromPosition);
            long valueOffset = fromPosition + Long.BYTES + keySize;
            long valueSize = mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, valueOffset);
            if (valueSize == -1) {
                return Long.BYTES + keySize + Long.BYTES;
            }
            return Long.BYTES + keySize + Long.BYTES + valueSize;
        }

        @Override
        public long getPointerKeySrcSize() {
            return mappedSSTable.get(ValueLayout.JAVA_LONG_UNALIGNED, fromPosition);
        }

        @Override
        public boolean hasNext() {
            return fromPosition <= toPosition;
        }

        @Override
        public Entry<MemorySegment> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entry<MemorySegment> entry = getByIndex(fromPosition);
            fromPosition += Utils.getEntrySize(entry);
            return entry;
        }
    }
}
