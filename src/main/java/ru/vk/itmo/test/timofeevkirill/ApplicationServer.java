package ru.vk.itmo.test.timofeevkirill;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ApplicationServer {

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void main(String[] args) throws IOException {
        Path workingDir = Path.of("/home/aphirri/IdeaProjects/2024-highload-dht"
                + "/src/main/java/ru/vk/itmo/test/timofeevkirill/tmp");
        Files.createDirectories(workingDir);

        TimofeevService service = new TimofeevService(
                new ServiceConfig(
                        8080,
                        "http://localhost",
                        List.of("http://localhost"),
                        workingDir
                )
        );

        service.start();
    }

    private ApplicationServer() {
    }
}
