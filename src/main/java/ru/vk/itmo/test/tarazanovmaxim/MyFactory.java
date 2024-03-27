package ru.vk.itmo.test.tarazanovmaxim;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

@ServiceFactory(stage = 3)
public class MyFactory implements ServiceFactory.Factory {

    @Override
    public Service create(ServiceConfig config) {
        return new MyService(config);
    }
}
