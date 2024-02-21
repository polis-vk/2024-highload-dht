package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.util.Iterator;

public interface DaoFileGet<D, E extends Entry<D>> {

    Iterator<E> get(D from, D to) throws IOException;

    E get(D key) throws IOException;
}
