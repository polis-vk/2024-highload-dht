package ru.vk.itmo.dao;

public interface Entry<D> {
    D key();

    D value();
}
