package ru.vk.itmo.test.kislovdanil.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.nio.file.Paths;

@ServiceFactory(stage = 1)
public class DatabaseServiceFactory implements ServiceFactory.Factory {
    @Override
    public Service create(ServiceConfig serverConfig) {
        Config daoConfig = new Config(
                serverConfig.workingDir().resolve(Paths.get("dao", "memtables")),
                1024 * 1024);
        try {
            return new DatabaseService(serverConfig, daoConfig);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
