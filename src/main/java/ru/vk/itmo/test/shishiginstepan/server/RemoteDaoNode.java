package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class RemoteDaoNode implements Dao<MemorySegment, Entry<MemorySegment>> {

    private final HttpClient client;

    private static final String BASE_REQUEST_PATH = "/v0/entity?id=";


    public RemoteDaoNode(String nodeUrl) {
        client = new HttpClient(new ConnectionString(nodeUrl)); // возможно стоит keepalive врубить
    }

    private static class RemoteDaoNodeFailure extends RuntimeException {
    }

    private static class RemoteNodeAccessFailure extends RuntimeException {
        public RemoteNodeAccessFailure(Exception e) {
            super(e);
        }
    }

    private String segmentToString(MemorySegment source) {
        return new String(source.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return null;
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        Response response;
        try {
            response = client.get(BASE_REQUEST_PATH + segmentToString(key));
        } catch (HttpException | PoolException | IOException e) {
            throw new RemoteNodeAccessFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteNodeAccessFailure(e);
        }
        if (response.getStatus() == 404) {
            return null;
        }
        MemorySegment value = MemorySegment.ofArray(response.getBody());
        return new BaseEntry<>(key, value);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        Response response;
        try {
            if (entry.value() == null) {
                response = client.delete(BASE_REQUEST_PATH + segmentToString(entry.key()));
            } else {
                response = client.put(
                        BASE_REQUEST_PATH + segmentToString(entry.key()),
                        entry.value().toArray(ValueLayout.JAVA_BYTE)
                );
            }
        } catch (HttpException | InterruptedException | PoolException | IOException e) {
            throw new RemoteNodeAccessFailure(e);
        }
        if (response.getStatus() >= 400) {
            throw new RemoteDaoNodeFailure();
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
