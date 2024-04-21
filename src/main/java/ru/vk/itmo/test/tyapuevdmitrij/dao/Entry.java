package ru.vk.itmo.test.tyapuevdmitrij.dao;

public interface Entry<D> {
    D key();

    D value();

    long timeStamp();
}
