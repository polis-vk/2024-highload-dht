package ru.vk.itmo.test.kovalevigor.server.strategy.util;

import one.nio.net.Session;
import one.nio.net.Socket;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;

public class JoinedQueueItem extends Session.ArrayQueueItem {

    private Iterator<MemorySegment> items;
    private MemorySegment current;
    private int itemOffset;

    public JoinedQueueItem(int bufferSize) {
        super(new byte[bufferSize], 0, 0, 0);
    }

    public void setItems(Iterator<MemorySegment> items) {
        this.items = items;
        nextItem();
        fillBuffer();
    }

    @Override
    public int write(Socket socket) throws IOException {
        int res;
        do {
            res = super.write(socket);
            fillIfNeeded();
        } while (res > 0 && remaining() > 0);
        return res;
    }

    private void fillIfNeeded() {
        if (super.remaining() == 0) {
            fillBuffer();
        }
    }

    private void fillBuffer() {
        offset = 0;
        count = 0;
        written = 0;
        while (count < data.length && currentItemRemain() > 0) {
            int length = (int) Math.min(currentItemRemain(), data.length - count);
            MemorySegment.copy(
                    current, ValueLayout.JAVA_BYTE, itemOffset,
                    data, count, length
            );
            count += length;
            itemOffset += length;
            while (currentItemRemain() == 0 && items.hasNext()) {
                nextItem();
            }
        }
    }

    private long currentItemRemain() {
        return current.byteSize() - itemOffset;
    }

    @Override
    public int remaining() {
        return super.remaining() + (currentItemRemain() > 0 ? 1 : 0);
    }

    private void nextItem() {
        do {
            current = items.next();
        } while (current == null && items.hasNext());
        itemOffset = 0;
    }
}

