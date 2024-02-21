package ru.vk.itmo.test.ryabovvadim.utils;

import ru.vk.itmo.test.ryabovvadim.iterators.FutureIterator;
import ru.vk.itmo.test.ryabovvadim.iterators.LazyIterator;

import java.util.Iterator;
import java.util.NoSuchElementException;

public final class IteratorUtils {
    public static <T> Iterator<T> emptyIterator() {
        return new Iterator<T>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public T next() {
                throw new NoSuchElementException();
            }
        };
    }

    public static <T> FutureIterator<T> emptyFutureIterator() {
        return new LazyIterator<>(
                () -> {
                    throw new NoSuchElementException();
                },
                () -> false
        );
    }

    private IteratorUtils() {
    }
}
