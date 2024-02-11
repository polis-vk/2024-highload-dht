package ru.vk.itmo.test;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ServiceFactory {
    int stage();

    interface Factory {
        Service create(ServiceConfig config);
    }
}
