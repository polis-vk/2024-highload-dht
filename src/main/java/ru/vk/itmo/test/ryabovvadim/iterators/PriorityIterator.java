package ru.vk.itmo.test.ryabovvadim.iterators;

public class PriorityIterator<T> implements FutureIterator<T> {
    private final FutureIterator<T> delegate;
    private final int priority;

    public PriorityIterator(FutureIterator<T> delegate, int priority) {
        this.delegate = delegate;
        this.priority = priority;
    }

    @Override
    public T showNext() {
        return delegate.showNext();
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public T next() {
        return delegate.next();
    }

    public int getPriority() {
        return priority;
    }
}
