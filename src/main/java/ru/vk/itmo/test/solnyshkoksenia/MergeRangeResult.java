package ru.vk.itmo.test.solnyshkoksenia;

import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.solnyshkoksenia.dao.MemorySegmentComparator;
import ru.vk.itmo.test.solnyshkoksenia.dao.MergeIterator;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class MergeRangeResult {
    private static final Comparator<MemorySegment> comparator = new MemorySegmentComparator();

    public static Iterator<Entry<MemorySegment>> range(Iterator<Entry<MemorySegment>> firstIterator,
                                                       List<Response> responses) {
        List<Iterator<Entry<MemorySegment>>> iterators = new ArrayList<>(responses.size() + 1);
        for (var response : responses) {
            iterators.add(iterator(response));
        }
        iterators.add(firstIterator);

        return new MergeIterator<>(iterators, (e1, e2) -> comparator.compare(e1.key(), e2.key())) {
            @Override
            protected boolean skip(Entry<MemorySegment> memorySegmentEntry) {
                return memorySegmentEntry.value() == null;
            }
        };
    }

    private static Iterator<Entry<MemorySegment>> iterator(Response response) {
        byte[] body = response.getBody();
        char separator = '\n';
        return new Iterator<>() {
            int offset = 0;

            @Override
            public boolean hasNext() {
                return offset < body.length;
            }

            @Override
            public Entry<MemorySegment> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                MemorySegment key = getMS();
                MemorySegment value = getMS();
                return new BaseEntry<>(key, value);
            }

            private MemorySegment getMS() {
                int startOffset = offset;

                byte b = body[offset];
                while (b != separator) {
                    b = body[++offset];
                }

                byte[] tmp = new byte[offset - startOffset];
                System.arraycopy(body, startOffset, tmp, 0, offset - startOffset);

                offset++;
                return MemorySegment.ofArray(tmp);
            }
        };
    }
}
