package ru.vk.itmo.test.georgiidalbeev;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

@ServiceFactory(stage = 3)
public class NewFactory implements ServiceFactory.Factory {

    @Override
    public Service create(ServiceConfig config) {
        return new NewService(config);
    }
}
