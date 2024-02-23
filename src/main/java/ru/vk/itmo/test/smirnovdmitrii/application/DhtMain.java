package ru.vk.itmo.test.smirnovdmitrii.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtProperties;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.PropertyException;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;
import ru.vk.itmo.test.smirnovdmitrii.server.DaoServiceImpl;

public class DhtMain {
    @DhtValue("local.selfUrl")
    private static String selfUrl;
    @DhtValue("local.selfPort")
    private static int selfPort;

    public static void main(String[] args) throws Exception {
        Path workingDirectory;
        if (args.length > 0) {
            workingDirectory = Path.of(args[0]);
            Files.createDirectories(workingDirectory);
            if (args.length > 1) {
                if (!"clear_on_init".equals(args[1])) {
                    throw new RuntimeException("unexpected argument: " + args[1]);
                }
                clearDirectory(workingDirectory);
            }
        } else {
            workingDirectory = Files.createTempDirectory(Path.of("."), "dao");
        }
        final ServiceConfig config = new ServiceConfig(
                selfPort,
                selfUrl,
                List.of(selfUrl),
                workingDirectory

        );
        final Service service = new DaoServiceImpl(config);
        service.start().get();
    }

    private static void clearDirectory(Path workingDirectory) throws IOException {
        Files.walkFileTree(workingDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static {
        try {
            DhtProperties.initProperties();
        } catch (final ClassNotFoundException e) {
            throw new PropertyException(e);
        }
    }
}
