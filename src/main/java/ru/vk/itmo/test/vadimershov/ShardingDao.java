package ru.vk.itmo.test.vadimershov;

import one.nio.http.HttpException;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;
import ru.vk.itmo.test.vadimershov.hash.ConsistentHashing;
import ru.vk.itmo.test.vadimershov.hash.VNode;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toByteArray;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toDeletedEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toEntity;
import static ru.vk.itmo.test.vadimershov.utils.MemorySegmentUtil.toMemorySegment;

public class ShardingDao {

    public static final String ENTITY_URI = "/v0/entity?id=";

    private final String selfUrl;
    private final Dao<MemorySegment, Entry<MemorySegment>> localDao;
    private final ConsistentHashing consistentHashing;

    public ShardingDao(ServiceConfig serviceConfig, Dao<MemorySegment, Entry<MemorySegment>> localDao) {
        this.selfUrl = serviceConfig.selfUrl();
        this.localDao = localDao;
        this.consistentHashing = new ConsistentHashing(serviceConfig.clusterUrls());
    }


    public byte[] get(String key) {
        VNode vNode = consistentHashing.findVNode(key);
        if (vNode.url().equals(selfUrl)) {
            try {
                Entry<MemorySegment> entry = localDao.get(toMemorySegment(key));
                if (entry == null) {
                    return null;
                }
                return toByteArray(entry.value());
            } catch (Exception e) {
                throw new DaoException(e);
            }
        }
        try {
            Response response = vNode.httpClient().get(ENTITY_URI + key);
            checkCodeInRemoteResp(vNode.url(), response);
            return response.getBody();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (PoolException | IOException | HttpException e) {
            throw new RuntimeException(e);
        }
    }

    public void upsert(String key, byte[] value) {
        VNode vNode = consistentHashing.findVNode(key);
        if (vNode.url().equals(selfUrl)) {
            try {
                localDao.upsert(toEntity(key, value));
            } catch (Exception e) {
                throw new DaoException(e);
            }
        }
        try {
            Response response = vNode.httpClient().put(ENTITY_URI + key, value);
            checkCodeInRemoteResp(vNode.url(), response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (PoolException | IOException | HttpException e) {
            throw new RuntimeException(e);
        }
    }

    public void delete(String key) {
        VNode vNode = consistentHashing.findVNode(key);
        if (vNode.url().equals(selfUrl)) {
            try {
                localDao.upsert(toDeletedEntity(key));
            } catch (Exception e) {
                throw new DaoException(e);
            }
        }
        try {
            Response response = vNode.httpClient().delete(ENTITY_URI + key);
            checkCodeInRemoteResp(vNode.url(), response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (PoolException | IOException | HttpException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkCodeInRemoteResp(String url, Response response) {
        switch (response.getStatus()) {
            case 400 -> throw new RemoteServiceException(DaoResponse.BAD_REQUEST, url);
            case 404 -> throw new RemoteServiceException(DaoResponse.NOT_FOUND, url);
            case 405 -> throw new RemoteServiceException(DaoResponse.METHOD_NOT_ALLOWED, url);
            case 429 -> throw new RemoteServiceException(DaoResponse.TOO_MANY_REQUESTS, url);
            case 500 -> throw new RemoteServiceException(DaoResponse.INTERNAL_ERROR, url);
            case 503 -> throw new RemoteServiceException(DaoResponse.SERVICE_UNAVAILABLE, url);
        }
    }

}
