package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

import java.nio.file.Path;

@ServiceFactory(stage = 1)
public class ServiceImlFactory implements ServiceFactory.Factory {
    @Override
    public Service create(ServiceConfig serviceConfig) {
        Config referenceDaoConfig = new Config(Path.of(serviceConfig.workingDir().toUri()), 1024 * 1024);
        return new ServiceIml(serviceConfig, referenceDaoConfig);
    }
}
