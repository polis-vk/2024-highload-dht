package ru.vk.itmo.test.vadimershov.hash;

import ru.vk.itmo.test.vadimershov.Pair;
import ru.vk.itmo.test.vadimershov.exceptions.DaoException;
import ru.vk.itmo.test.vadimershov.exceptions.NotFoundException;
import ru.vk.itmo.test.vadimershov.exceptions.RemoteServiceException;

public abstract class VirtualNode {
    private final String url;
    private final int replicaIndex;

    public VirtualNode(String url, int replicaIndex) {
        this.url = url;
        this.replicaIndex = replicaIndex;
    }

    public String url() {
        return this.url;
    }

    public String key(String url) {
        return "Virtual node: " + url + "-" + this.replicaIndex;
    }

    public abstract void close();
    public abstract Pair<byte[], Long> get(String key) throws NotFoundException, DaoException, RemoteServiceException;
    public abstract void upsert(String key, byte[] value, Long timestamp) throws DaoException, RemoteServiceException;
    public abstract void delete(String key, Long timestamp) throws DaoException, RemoteServiceException;

}
