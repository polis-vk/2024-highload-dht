package ru.vk.itmo.test.kislovdanil.service;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.kislovdanil.dao.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Paths;

@ServiceFactory(stage = 4)
public class DatabaseServiceFactory implements ServiceFactory.Factory {
    @Override
    public Service create(ServiceConfig serverConfig) {
        Config daoConfig = new Config(
                serverConfig.workingDir().resolve(Paths.get("dao", "memtables")),
                1024 * 1024 * 10);
        try {
            return new DatabaseService(serverConfig, daoConfig);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
