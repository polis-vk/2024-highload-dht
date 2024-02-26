package ru.vk.itmo.test.smirnovdmitrii.application.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DhtProperties {
    public static final String CLASSES_PACKAGE = "ru.vk.itmo.test.smirnovdmitrii";
    public static final String PROPERTIES_FILE_PATH = "smirnovdmitrii/application";
    private static final Logger logger = LoggerFactory.getLogger(DhtProperties.class);
    private static final Map<String, String> properties = new HashMap<>();
    private static final AtomicBoolean isPropertiesSet = new AtomicBoolean(false);

    static {
        ResourceBundle bundle = ResourceBundle.getBundle(PROPERTIES_FILE_PATH);
        for (final Enumeration<String> keyEnumeration = bundle.getKeys();
             keyEnumeration.hasMoreElements();
        ) {
            final String key = keyEnumeration.nextElement();
            properties.put(key, bundle.getString(key));
        }
    }

    private DhtProperties() {
    }

    public static void initProperties() throws ClassNotFoundException {
        initProperties(CLASSES_PACKAGE);
    }

    public static void initProperties(String classesPackage) throws ClassNotFoundException {
        if (!isPropertiesSet.compareAndSet(false, true)) {
            return;
        }
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        final String classesPathString = classesPackage.replace(".", "/");
        final List<Path> directories = new ArrayList<>();
        // getting the highest packages
        try {
            if (classLoader == null) {
                throw new ClassNotFoundException("Can't get class loader");
            }
            final Enumeration<URL> resources = classLoader.getResources(classesPathString);
            while (resources.hasMoreElements()) {
                directories.add(Path.of(URLDecoder.decode(resources.nextElement().getPath(), StandardCharsets.UTF_8)));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        for (final Path path : directories) {
            try {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().endsWith(".class")) {
                            final String filePathString = file.toString();
                            final String classPath = filePathString.substring(
                                    filePathString.indexOf(classesPathString), filePathString.length() - 6
                            );
                            try {
                                handleClass(Class.forName(classPath.replace("/", ".")));
                            } catch (final ClassNotFoundException e) {
                                logger.error(e.getMessage());
                                throw new PropertyException(e);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void handleClass(final Class<?> clazz) {
        final Field[] fields = clazz.getDeclaredFields();
        for (final Field field: fields) {
            final DhtValue dhtValue = field.getAnnotation(DhtValue.class);
            if (dhtValue == null) {
                continue;
            }
            final int modifiers = field.getModifiers();
            if (Modifier.isAbstract(modifiers)) {
                throwModifierException(clazz, field, "abstract");
            }
            if (Modifier.isFinal(modifiers)) {
                throwModifierException(clazz, field, "final");
            }
            if (!Modifier.isStatic(modifiers)) {
                throwModifierException(clazz, field, "not static");
            }
            final String value = properties.get(dhtValue.value());
            if (value == null) {
                throw new PropertyException(
                        "property '" + dhtValue.value() + "' not found, annotated field " + field.getName()
                );
            }
            field.setAccessible(true);
            final Class<?> fieldType = field.getType();
            final Object valueToSet = convertToFieldClass(clazz, field, fieldType, value);
            try {
                field.set(null, valueToSet);
            } catch (final IllegalAccessException e) {
                throw new PropertyException(e);
            }
        }
    }

    private static void throwModifierException(
            final Class<?> clazz,
            final Field field,
            final String message
    ) {
        throw new PropertyException("field " + field.getName()
                + " is " + message + ", class " + clazz.getName());
    }

    private static Object convertToFieldClass(
            final Class<?> clazz,
            final Field field,
            final Class<?> fieldType,
            final String value
    ) {
        Object valueToSet = null;
        if (String.class.isAssignableFrom(fieldType)) {
           valueToSet = value;
        } else if (int.class.isAssignableFrom(fieldType)) {
            valueToSet = Integer.valueOf(value);
        } else if (long.class.isAssignableFrom(fieldType)) {
            valueToSet = Long.valueOf(value);
        } else if (short.class.isAssignableFrom(fieldType)) {
            valueToSet = Short.valueOf(value);
        } else if (double.class.isAssignableFrom(fieldType)) {
            valueToSet = Double.valueOf(value);
        } else if (boolean.class.isAssignableFrom(fieldType)) {
            valueToSet = Boolean.valueOf(value);
        } else if (Enum.class.isAssignableFrom(fieldType)) {
            final Object[] enumValues = fieldType.getEnumConstants();
            for (final Object enumValue: enumValues) {
                if (enumValue.toString().equals(value)) {
                    valueToSet = enumValue;
                    break;
                }
            }
            if (valueToSet == null) {
                throw new PropertyException("No enum constraint for field "
                        + field.getName() + " with name " + value);
            }
        } else {
            throw new PropertyException("not supported field type '" + fieldType.getName()
                    + ", class " + clazz.getName());
        }
        return valueToSet;
    }
}
