package ru.vk.itmo.test.smirnovdmitrii.dao.util;

public interface UpgradableReadWriteLock {

    boolean tryWriteLock();

    boolean tryReadLock();

    void readUnlock();

    void writeUnlock();
}
