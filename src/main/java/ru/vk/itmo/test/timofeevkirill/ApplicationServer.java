package ru.vk.itmo.test.timofeevkirill;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static ru.vk.itmo.test.timofeevkirill.Settings.BASE_URL;
import static ru.vk.itmo.test.timofeevkirill.Settings.CLUSTER_SIZE;

public final class ApplicationServer {

    @SuppressWarnings("FutureReturnValueIgnored")
    public static void main(String[] args) throws IOException {
        int port = 8080;
        List<Integer> ports = new ArrayList<>(CLUSTER_SIZE);
        List<String> clusterUrls = new ArrayList<>(CLUSTER_SIZE);
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            ports.add(port);
            clusterUrls.add(BASE_URL + ports.get(i));
            port += 1;
        }

        for (int i = 0; i < CLUSTER_SIZE; i++) {
            Path workingDir = Path.of("/home/aphirri/IdeaProjects/2024-highload-dht"
                    + "/src/main/java/ru/vk/itmo/test/timofeevkirill/tmp" + ports.get(i));
            Files.createDirectories(workingDir);

            TimofeevService service =
                    new TimofeevService(
                            new ServiceConfig(ports.get(i), clusterUrls.get(i), clusterUrls, workingDir)
                    );

            service.start();
        }
    }

    private ApplicationServer() {
    }
}
