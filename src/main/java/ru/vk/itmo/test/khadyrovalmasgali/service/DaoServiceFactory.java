package ru.vk.itmo.test.khadyrovalmasgali.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

@ServiceFactory(stage = 6)
public class DaoServiceFactory implements ServiceFactory.Factory {
    @Override
    public Service create(ServiceConfig config) {
        return new DaoService(config);
    }
}
