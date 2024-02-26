package ru.vk.itmo.test.smirnovdmitrii.application.properties;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface DhtValue {
    String value();
}
