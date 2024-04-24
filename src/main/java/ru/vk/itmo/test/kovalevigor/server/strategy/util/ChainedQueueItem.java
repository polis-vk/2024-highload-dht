package ru.vk.itmo.test.kovalevigor.server.strategy.util;

import one.nio.net.Session;
import one.nio.net.Socket;

import java.io.IOException;
import java.util.Iterator;

public class ChainedQueueItem extends Session.QueueItem {

    private final Iterator<? extends Session.QueueItem> items;
    private Session.QueueItem current;

    public ChainedQueueItem(Iterator<? extends Session.QueueItem> items) {
        this.items = items;
        current = items.next();
    }

    @Override
    public int write(Socket socket) throws IOException {
        int res;
        do {
            res = current.write(socket);
            if (current.remaining() == 0 && items.hasNext()) {
                current = items.next();
            }
        } while (res > 0 && remaining() > 0);
        return res;
    }

    @Override
    public int remaining() {
        return current.remaining() + (items.hasNext() ? 1 : 0);
    }
}
