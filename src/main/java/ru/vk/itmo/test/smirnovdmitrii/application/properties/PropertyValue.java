package ru.vk.itmo.test.smirnovdmitrii.application.properties;

import javax.annotation.processing.Generated;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface PropertyValue {
    public String value();
}
