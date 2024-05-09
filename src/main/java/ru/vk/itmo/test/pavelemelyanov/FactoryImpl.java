package ru.vk.itmo.test.pavelemelyanov;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

@ServiceFactory(stage = 5)
public class FactoryImpl implements ServiceFactory.Factory {

    @Override
    public Service create(ServiceConfig config) {
        return new ServiceImpl(config);
    }
}
