package ru.vk.itmo.test.kovalevigor.dao.entry;

public abstract class BaseDaoEntry<D> implements DaoEntry<D> {

    private final D key;
    private final D value;

    protected BaseDaoEntry(D key, D value) {
        this.key = key;
        this.value = value;
    }

    @Override
    public D key() {
        return key;
    }

    @Override
    public D value() {
        return value;
    }
}
