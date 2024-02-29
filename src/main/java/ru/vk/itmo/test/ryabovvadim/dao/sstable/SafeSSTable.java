package ru.vk.itmo.test.ryabovvadim.dao.sstable;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ryabovvadim.dao.iterators.FutureIterator;
import ru.vk.itmo.test.ryabovvadim.dao.iterators.LazyIterator;
import ru.vk.itmo.test.ryabovvadim.dao.utils.FileUtils;
import ru.vk.itmo.test.ryabovvadim.dao.utils.IteratorUtils;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

import static ru.vk.itmo.test.ryabovvadim.dao.utils.FileUtils.DATA_FILE_EXT;
import static ru.vk.itmo.test.ryabovvadim.dao.utils.FileUtils.DELETED_FILE_EXT;

public class SafeSSTable {
    private boolean deleted;
    private final SSTable ssTable;
    private final AtomicInteger countAliveRef = new AtomicInteger();

    public SafeSSTable(SSTable ssTable) {
        this.deleted = false;
        this.ssTable = ssTable;
    }

    public SSTable ssTable() {
        return ssTable;
    }

    public void setDeleted() {
        this.deleted = true;
    }

    public Entry<MemorySegment> findEntry(MemorySegment key) {
        if (deleted || incrementRef()) {
            return null;
        }

        Entry<MemorySegment> result = ssTable.findEntry(key);
        decrementRef();
        return result;
    }

    public FutureIterator<Entry<MemorySegment>> findEntries(MemorySegment from, MemorySegment to) {
        if (deleted || incrementRef()) {
            return IteratorUtils.emptyFutureIterator();
        }

        FutureIterator<Entry<MemorySegment>> iterator = ssTable.findEntries(from, to);
        if (!iterator.hasNext()) {
            return iterator;
        }

        return new LazyIterator<>(
                () -> {
                    Entry<MemorySegment> next = iterator.next();
                    if (!iterator.hasNext()) {
                        decrementRef();
                    }
                    return next;
                },
                iterator::hasNext
        );
    }

    public void delete(Path path) throws IOException {
        countAliveRef.getAndUpdate(x -> -(x + 1));
        Path dataFile = FileUtils.makePath(path, Long.toString(ssTable.getId()), DATA_FILE_EXT);
        Path deletedFile = FileUtils.makePath(path, Long.toString(ssTable.getId()), DELETED_FILE_EXT);
        Files.move(dataFile, deletedFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        synchronized (countAliveRef) {
            try {
                while (!Thread.interrupted() && countAliveRef.get() != -1) {
                    countAliveRef.wait();
                }
                Files.deleteIfExists(deletedFile);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean incrementRef() {
        return countAliveRef.getAndUpdate(x -> x < 0 ? x : x + 1) < 0;
    }

    private void decrementRef() {
        if (countAliveRef.updateAndGet(x -> x < 0 ? x + 1 : x - 1) == -1) {
            synchronized (countAliveRef) {
                countAliveRef.notifyAll();
            }
        }
    }
}
