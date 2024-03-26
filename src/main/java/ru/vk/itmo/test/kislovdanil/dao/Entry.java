package ru.vk.itmo.test.kislovdanil.dao;

public interface Entry<D> {
    D key();

    D value();
    
    long timestamp();
}
