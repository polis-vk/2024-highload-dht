package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import static ru.vk.itmo.test.emelyanovvitaliy.DhtServer.TIMESTAMP_HEADER;

public class RemoteDaoMediator extends DaoMediator {
    protected static final int DEFAULT_TIMEOUT = 100;
    protected final HttpClient client;
    protected final int timeout;

    RemoteDaoMediator(HttpClient httpClient) {
        this(httpClient, DEFAULT_TIMEOUT);
    }

    RemoteDaoMediator(HttpClient httpClient, int timeout) {
        this.client = httpClient;
        this.timeout = timeout;
    }

    protected boolean isStatusCodeCorrect(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    @Override
    void stop() {
        if (!client.isClosed()) {
            client.close();
        }
    }

    @Override
    boolean isStopped() {
        return client.isClosed();
    }

    @Override
    protected boolean put(Request request) {
        return simpleForward(request);
    }

    protected boolean simpleForward(Request request) {
        try {
            Response response = client.invoke(request);
            return isStatusCodeCorrect(response.getStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedInterruptedException(e);
        } catch (PoolException | IOException | HttpException e) {
            return false;
        }
    }

    @Override
    TimestampedEntry<MemorySegment> get(Request request) {
        Response response = null;
        try {
            response = client.invoke(request, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedInterruptedException(e);
        } catch (PoolException | IOException | HttpException e) {
            return null;
        }
        String timestampHeader = response.getHeader(TIMESTAMP_HEADER);
        long timestamp;
        if (timestampHeader == null) {
            timestamp = NEVER_TIMESTAMP;
        } else {
            timestamp = Long.parseLong(timestampHeader);
        }
        if (isStatusCodeCorrect(response.getStatus()) && timestampHeader != null) {
            return new TimestampedEntry<>(
                    keyFor(request.getParameter(DhtServer.ID_KEY)),
                    MemorySegment.ofArray(response.getBody()),
                    timestamp
            );
        } else if (response.getStatus() == 404) {
            return new TimestampedEntry<>(
                    keyFor(request.getParameter(DhtServer.ID_KEY)),
                    null,
                    timestamp
            );
        } else {
            return null;
        }
    }

    @Override
    boolean delete(Request request) {
        return simpleForward(request);
    }
}
