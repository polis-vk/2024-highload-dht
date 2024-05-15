package ru.vk.itmo.test.viktorkorotkikh;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

@ServiceFactory(stage = 7)
public class LSMServiceFactoryImpl implements ServiceFactory.Factory {
    @Override
    public Service create(ServiceConfig config) {
        return new LSMServiceImpl(config);
    }
}
