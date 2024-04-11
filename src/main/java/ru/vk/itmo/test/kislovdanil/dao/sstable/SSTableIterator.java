package ru.vk.itmo.test.kislovdanil.dao.sstable;

import ru.vk.itmo.test.kislovdanil.dao.Entry;
import ru.vk.itmo.test.kislovdanil.dao.iterators.DatabaseIterator;

import java.lang.foreign.MemorySegment;

class SSTableIterator implements DatabaseIterator {
    private long curItemIndex;
    private final MemorySegment maxKey;

    private Entry<MemorySegment> curEntry;
    private final SSTable table;

    public SSTableIterator(MemorySegment minKey, MemorySegment maxKey, SSTable table) {
        this.table = table;
        this.maxKey = maxKey;
        if (table.size == 0) return;
        if (minKey == null) {
            this.curItemIndex = 0;
        } else {
            this.curItemIndex = this.table.findByKey(minKey);
        }
        if (curItemIndex == -1) {
            curItemIndex = Long.MAX_VALUE;
        } else {
            this.curEntry = this.table.readEntry(curItemIndex);
        }
    }

    @Override
    public boolean hasNext() {
        if (curItemIndex >= this.table.size) return false;
        return maxKey == null || table.memSegComp.compare(curEntry.key(), maxKey) < 0;
    }

    @Override
    public Entry<MemorySegment> next() {
        Entry<MemorySegment> result = curEntry;
        curItemIndex++;
        if (curItemIndex < table.size) {
            curEntry = table.readEntry(curItemIndex);
        }
        return result;
    }

    @Override
    public long getPriority() {
        return table.getTableId();
    }
}
