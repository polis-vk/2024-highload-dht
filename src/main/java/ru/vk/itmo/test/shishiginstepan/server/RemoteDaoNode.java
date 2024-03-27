package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.shishiginstepan.dao.EntryWithTimestamp;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

public class RemoteDaoNode implements Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> {

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
    public Iterator<EntryWithTimestamp<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return null;
    }

    @Override
    public EntryWithTimestamp<MemorySegment> get(MemorySegment key) {
        Response response;
        String innerRequest = "X-inner-request: " + 1;
        try {
            response = client.get(BASE_REQUEST_PATH + segmentToString(key), innerRequest);
        } catch (HttpException | PoolException | IOException e) {
            throw new RemoteNodeAccessFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteNodeAccessFailure(e);
        }
        if (response.getStatus() == 404) {
            var timestamp = response.getHeader("X-timestamp");
            if (timestamp != null) {
                return new EntryWithTimestamp<>(
                        key,
                        null,
                        Long.parseLong(response.getHeader("X-timestamp").substring(2))
                );
            }
            return new EntryWithTimestamp<>(key, null, 0L);
        }
        MemorySegment value = MemorySegment.ofArray(response.getBody());
        Long timestamp = Long.parseLong(response.getHeader("X-timestamp").substring(2));
        return new EntryWithTimestamp<MemorySegment>(key, value, timestamp);
    }

    @Override
    public void upsert(EntryWithTimestamp<MemorySegment> entry) {
        Response response;
        String timestampHeader = "X-timestamp: " + entry.timestamp();
        String innerRequest = "X-inner-request: " + 1;
        try {
            if (entry.value() == null) {
                response = client.delete(
                        BASE_REQUEST_PATH + segmentToString(entry.key()),
                        timestampHeader, innerRequest
                );
            } else {
                response = client.put(
                        BASE_REQUEST_PATH + segmentToString(entry.key()),
                        entry.value().toArray(ValueLayout.JAVA_BYTE),
                        timestampHeader,
                        innerRequest
                );
            }
        } catch (HttpException | PoolException | IOException e) {
            throw new RemoteNodeAccessFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
