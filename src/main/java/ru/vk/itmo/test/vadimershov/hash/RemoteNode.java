package ru.vk.itmo.test.vadimershov.hash;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.vadimershov.DaoResponse;
import ru.vk.itmo.test.vadimershov.Pair;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Objects;

public class RemoteNode extends VirtualNode {

    public static final String X_INNER_TRUE = "X-inner: true";
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    public static final String ENTITY_URI = "/v0/entity?id=";

    private final HttpClient httpClient;

    public RemoteNode(String url, @Nonnull HttpClient httpClient, int replicaIndex) {
        super(url, replicaIndex);
        this.httpClient = httpClient;
    }

    @Override
    public void close() {
        if (!this.httpClient.isClosed()) {
            this.httpClient.close();
        }
    }

    @Override
    public Pair<byte[], Long> get(String key) throws DaoException, RemoteServiceException {
        Response response;
        try {
            response = this.httpClient.get(ENTITY_URI + key, X_INNER_TRUE);
        } catch (InterruptedException e) {
            logger.error("Can't get with key={} in remote node url={}", key, this.url(), e);
            Thread.currentThread().interrupt();
            throw new DaoException("Can't get value from remote node", e);
        } catch (PoolException | IOException | HttpException e) {
            logger.error("Can't get with key={} in remote node url={}", key, this.url(), e);
            throw new DaoException("Can't get value from remote node", e);
        }
        Long timestamp = getTimestamp(response);
        if (response.getStatus() == 404) {
            return new Pair<>(null, Objects.requireNonNullElse(timestamp, 0L));
        }
        return new Pair<>(response.getBody(), timestamp);
    }

    private static Long getTimestamp(Response response) {
        String timestamp = response.getHeader(DaoResponse.X_TIMESTAMP);
        return timestamp == null ? null : Long.parseLong(timestamp);
    }

    @Override
    public void upsert(String key, byte[] value, Long timestamp) throws DaoException, RemoteServiceException {
        Response response;
        try {
            response = this.httpClient.put(ENTITY_URI + key, value, X_INNER_TRUE, DaoResponse.X_TIMESTAMP + timestamp);
        } catch (InterruptedException e) {
            logger.error("InterruptedException upsert by key={} in remote node url={}", key, this.url(), e);
            Thread.currentThread().interrupt();
            throw new DaoException("Can't upsert value in remote node", e);
        } catch (PoolException | IOException | HttpException e) {
            logger.error("Exception upsert by key={} in service url={}", key, this.url(), e);
            throw new DaoException("Can't upsert value in remote node", e);
        }
        checkCodeInRemoteResp(this.url(), response);
    }

    @Override
    public void delete(String key, Long timestamp) throws DaoException, RemoteServiceException {
        Response response;
        try {
            response = this.httpClient.delete(ENTITY_URI + key, X_INNER_TRUE, DaoResponse.X_TIMESTAMP + timestamp);
        } catch (InterruptedException e) {
            logger.error("InterruptedException delete by key={} in service url={}", key, this.url(), e);
            Thread.currentThread().interrupt();
            throw new DaoException("Can't delete value in remote node", e);
        } catch (PoolException | IOException | HttpException e) {
            logger.error("Exception delete by key={} in service url={}", key, this.url(), e);
            throw new DaoException("Can't delete value in remote node", e);
        }
        checkCodeInRemoteResp(this.url(), response);
    }

    private void checkCodeInRemoteResp(String url, Response response) throws RemoteServiceException {
        switch (response.getStatus()) {
            case 200, 201, 202, 404 -> { /* correct http code */ }
            case 400 -> throw new RemoteServiceException(DaoResponse.BAD_REQUEST, url);
            case 405 -> throw new RemoteServiceException(DaoResponse.METHOD_NOT_ALLOWED, url);
            case 429 -> throw new RemoteServiceException(DaoResponse.NOT_ENOUGH_REPLICAS, url);
            case 503 -> throw new RemoteServiceException(DaoResponse.SERVICE_UNAVAILABLE, url);
            default -> throw new RemoteServiceException(DaoResponse.INTERNAL_ERROR, url);
        }
    }

}
