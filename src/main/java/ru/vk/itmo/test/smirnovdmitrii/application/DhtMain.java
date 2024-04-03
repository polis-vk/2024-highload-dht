package ru.vk.itmo.test.smirnovdmitrii.application;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtProperties;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.PropertyException;
import ru.vk.itmo.test.smirnovdmitrii.server.DaoServiceImpl;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class DhtMain {
    @DhtValue("local.hosts")
    private static String hosts;
    @DhtValue("local.ports")
    private static String selfPorts;
    @DhtValue("local.protocol:http")
    private static String protocol;

    private DhtMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException, ExecutionException {
        Path workingDirectory;
        if (args.length > 0) {
            workingDirectory = Path.of(args[0]);
            Files.createDirectories(workingDirectory);
            if (args.length > 1) {
                if (!"clear_on_init".equals(args[1])) {
                    return;
                }
                clearDirectory(workingDirectory);
            }
        } else {
            workingDirectory = Files.createTempDirectory(Path.of("."), "dao");
        }
        final int[] ports = getPorts();
        final List<String> selfUrls = getSelfUrls(ports);
        for (int i = 0; i < ports.length; i++) {
            final int port = ports[i];
            final Path currentServiceWorkingDirectory = workingDirectory.resolve(Integer.toString(port));
            Files.createDirectories(currentServiceWorkingDirectory);
            startService(
                currentServiceWorkingDirectory,
                port,
                selfUrls.get(i),
                selfUrls
            );
        }
    }

    private static List<String> getSelfUrls(
            final int... ports
    ) {
        final String[] splitHosts = splitAt(hosts);
        final List<String> urls = new ArrayList<>(splitHosts.length);
        for (int i = 0; i < splitHosts.length; i++) {
            urls.add(protocol + "://" + splitHosts[i] + ":" + ports[i]);
        }
        return urls;
    }

    private static int[] getPorts() {
        final String[] portStrings = splitAt(selfPorts);
        final int[] ports = new int[portStrings.length];
        for (int i = 0; i < portStrings.length; i++) {
            ports[i] = Integer.parseInt(portStrings[i]);
        }
        return ports;
    }

    private static String[] splitAt(
            final String str
    ) {
        final int strLength = str.length();
        int count = 1;
        for (int i = 0; i < strLength; i++) {
            final char cur = str.charAt(i);
            if (cur == ',') {
                count++;
            }
        }
        final String[] split = new String[count];
        int curIdx = 0;
        for (int i = 0; i < split.length; i++) {
            final int startIdx = curIdx;
            while (curIdx < strLength) {
                final char cur = str.charAt(curIdx);
                if (cur == ',') {
                    break;
                }
                curIdx++;
            }
            split[i] = str.substring(startIdx, curIdx);
            curIdx++;
        }
        return split;
    }

    private static void startService(
            final Path workingDirectory,
            final int port,
            final String selfUrl,
            final List<String> selfUrls
    ) throws IOException, ExecutionException, InterruptedException {
        new DaoServiceImpl(
                new ServiceConfig(
                        port,
                        selfUrl,
                        selfUrls,
                        workingDirectory
                )
        ).start().get();
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
