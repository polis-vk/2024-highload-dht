package ru.vk.itmo.test.kovalevigor.server.strategy.util;

import one.nio.net.Session;
import one.nio.net.Socket;

import java.io.IOException;
import java.util.Iterator;

public class JoinedQueueItem extends Session.ArrayQueueItem {

    private final Iterator<ByteStorage> items;
    private ByteStorage current;
    private int itemOffset = 0;

    public JoinedQueueItem(int bufferSize, Iterator<ByteStorage> items) {
        super(new byte[bufferSize], 0, 0, 0);
        this.items = items;
        this.current = items.next();
        fillBuffer();
    }

    @Override
    public int write(Socket socket) throws IOException {
        int written = super.write(socket);
        if (remaining() == 0) {
            fillBuffer();
        }
        return written;
    }

    private void fillBuffer() {
        if (super.remaining() == 0) {
            offset = 0;
            count = 0;
            written = 0;
            while (count < data.length && currentItemRemain() > 0) {
                int length = (int) Math.min(currentItemRemain(), data.length - count);
                current.get(itemOffset, data, count, length);
                count += length;
                itemOffset += length;
                while (currentItemRemain() == 0 && items.hasNext()) {
                    current = items.next();
                    itemOffset = 0;
                }
            }
        }
    }

    private long currentItemRemain() {
        return current.size() - itemOffset;
    }

    @Override
    public int remaining() {
        return super.remaining() + (currentItemRemain() > 0 ? 1 : 0);
    }
}

