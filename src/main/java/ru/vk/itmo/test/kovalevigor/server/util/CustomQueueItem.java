package ru.vk.itmo.test.kovalevigor.server.util;

import one.nio.net.Session;
import one.nio.net.Socket;
import ru.vk.itmo.test.kovalevigor.dao.iterators.ShiftedIterator;

import java.io.IOException;

public class CustomQueueItem extends Session.ArrayQueueItem {

    private final ShiftedIterator<byte[]> iterator;

    public CustomQueueItem(ShiftedIterator<byte[]> iterator) {
        super(iterator.getValue(), 0, iterator.next().length, 0);
        this.iterator = iterator;
    }

    @Override
    public int write(Socket socket) throws IOException {
        int written = super.write(socket);
        if (super.remaining() == 0 && iterator.hasNext()) {
            byte[] data = iterator.next();
            this.data = data;
            this.offset = 0;
            this.count = data.length;
            this.written = 0;
        }
        return written;
    }

    @Override
    public int remaining() {
        return super.remaining() + (iterator.hasNext() ? 1 : 0);
    }
}
