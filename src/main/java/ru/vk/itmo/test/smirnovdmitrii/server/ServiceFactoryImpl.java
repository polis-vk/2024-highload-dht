package ru.vk.itmo.test.smirnovdmitrii.server;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.io.UncheckedIOException;

@ServiceFactory(stage = 3)
public class ServiceFactoryImpl implements ServiceFactory.Factory {
    @Override
    public Service create(ServiceConfig config) {
        try {
            return new DaoServiceImpl(config);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
