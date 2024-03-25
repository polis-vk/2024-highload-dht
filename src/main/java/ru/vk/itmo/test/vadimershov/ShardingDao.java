package ru.vk.itmo.test.vadimershov;

import one.nio.http.HttpException;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.NotFoundException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;
import ru.vk.itmo.test.vadimershov.hash.ConsistentHashing;
import ru.vk.itmo.test.vadimershov.hash.VirtualNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;

import static java.lang.StringTemplate.STR;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toByteArray;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toDeletedEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toMemorySegment;

public class ShardingDao {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    public static final String ENTITY_URI = "/v0/entity?id=";

    private final String selfUrl;
    private final Dao<MemorySegment, Entry<MemorySegment>> localDao;
    private final ConsistentHashing consistentHashing;

    public ShardingDao(ServiceConfig serviceConfig, Config daoConfig) throws IOException {
        this.selfUrl = serviceConfig.selfUrl();
        this.localDao = new ReferenceDao(daoConfig);
        this.consistentHashing = new ConsistentHashing(serviceConfig.clusterUrls());
    }

    public byte[] get(String key) throws NotFoundException, DaoException, RemoteServiceException {
        VirtualNode virtualNode = consistentHashing.findVNode(key);
        if (virtualNode.url().equals(selfUrl)) {
            Entry<MemorySegment> entry;
            try {
                entry = localDao.get(toMemorySegment(key));
            } catch (Exception e) {
                logger.error(STR."Can't get value by key=\{key}", e);
                throw new DaoException(STR."Can't get value by key=\{key}", e);
            }
            if (entry == null) {
                throw new NotFoundException();
            }
            return toByteArray(entry.value());
        }

        Response response;
        try {
            response = virtualNode.httpClient().get(ENTITY_URI + key);
        } catch (InterruptedException e) {
            logger.error("Can't get with key={} in remote node url={}", key, virtualNode.url(), e);
            Thread.currentThread().interrupt();
            throw new DaoException("Can't get value from remote node", e);
        } catch (PoolException | IOException | HttpException e) {
            logger.error("Can't get with key={} in remote node url={}", key, virtualNode.url(), e);
            throw new DaoException("Can't get value from remote node", e);
        }
        checkCodeInRemoteResp(virtualNode.url(), response);
        return response.getBody();
    }

    public void upsert(String key, byte[] value) throws DaoException, RemoteServiceException {
        VirtualNode virtualNode = consistentHashing.findVNode(key);
        if (virtualNode.url().equals(selfUrl)) {
            try {
                localDao.upsert(toEntity(key, value));
                return;
            } catch (Exception e) {
                logger.error("Can't upsert value by key={}", key, e);
                throw new DaoException("Can't upsert value", e);
            }
        }

        Response response;
        try {
            response = virtualNode.httpClient().put(ENTITY_URI + key, value);
        } catch (InterruptedException e) {
            logger.error("InterruptedException upsert by key={} in remote node url={}", key, virtualNode.url(), e);
            Thread.currentThread().interrupt();
            throw new DaoException("Can't upsert value from remote node", e);
        } catch (PoolException | IOException | HttpException e) {
            logger.error("Exception upsert by key={} in service url={}", key, virtualNode.url(), e);
            throw new DaoException("Can't upsert value from remote node", e);
        }
        checkCodeInRemoteResp(virtualNode.url(), response);
    }

    public void delete(String key) throws DaoException, RemoteServiceException {
        VirtualNode virtualNode = consistentHashing.findVNode(key);
        if (virtualNode.url().equals(selfUrl)) {
            try {
                localDao.upsert(toDeletedEntity(key));
                return;
            } catch (Exception e) {
                logger.error(STR."Can't delete by key=\{key}", key, virtualNode.url(), e);
                throw new DaoException(STR."Can't delete by key=\{key}", e);
            }
        }

        Response response;
        try {
            response = virtualNode.httpClient().delete(ENTITY_URI + key);
        } catch (InterruptedException e) {
            logger.error("InterruptedException delete by key={} in service url={}", key, virtualNode.url(), e);
            Thread.currentThread().interrupt();
            throw new DaoException("Can't delete value from remote node", e);
        } catch (PoolException | IOException | HttpException e) {
            logger.error("Exception delete by key={} in service url={}", key, virtualNode.url(), e);
            throw new DaoException("Can't delete value from remote node", e);
        }
        checkCodeInRemoteResp(virtualNode.url(), response);
    }

    public void close() {
        try {
            this.localDao.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.consistentHashing.close();
    }

    private void checkCodeInRemoteResp(String url, Response response) throws RemoteServiceException {
        switch (response.getStatus()) {
            case 200, 201, 202 -> { /* correct http code */ }
            case 400 -> throw new RemoteServiceException(DaoResponse.BAD_REQUEST, url);
            case 404 -> throw new RemoteServiceException(DaoResponse.NOT_FOUND, url);
            case 405 -> throw new RemoteServiceException(DaoResponse.METHOD_NOT_ALLOWED, url);
            case 429 -> throw new RemoteServiceException(DaoResponse.TOO_MANY_REQUESTS, url);
            case 503 -> throw new RemoteServiceException(DaoResponse.SERVICE_UNAVAILABLE, url);
            default -> throw new RemoteServiceException(DaoResponse.INTERNAL_ERROR, url);
        }
    }

}
