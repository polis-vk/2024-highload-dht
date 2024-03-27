package ru.vk.itmo.test.nikitaprokopev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public final class ServerStarter {

    private static final String DIRECTORY = "tmp/dao/";

    private ServerStarter() {

    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args[0]);
        List<String> cluster = Arrays.asList(args).subList(1, args.length);

        Path directoryPath = Paths.get(DIRECTORY + port);
        if (!Files.exists(directoryPath)) {
            Files.createDirectories(directoryPath);
        }

        MyService service = new MyService(
                new ServiceConfig(
                        port,
                        "http://localhost:" + port,
                        cluster,
                        directoryPath
                )
        );

        service.start();
    }
}
