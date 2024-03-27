
package ru.vk.itmo.test.khadyrovalmasgali;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.khadyrovalmasgali.service.DaoService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Main {
    public static void main(String[] args) throws IOException {
        Logger log = LoggerFactory.getLogger(Main.class);
        String selfUrl = "http://localhost";
        int[] ports = {8080, 8090};
        List<String> cluserUrls = new ArrayList<>();
        for (int port : ports) {
            cluserUrls.add(selfUrl + port);
        }
        List<ServiceConfig> clusterConfs = new ArrayList<>();
        for (int i = 0; i < ports.length; ++i) {
            clusterConfs.add(new ServiceConfig(
                    ports[i],
                    cluserUrls.get(i),
                    cluserUrls,
                    Files.createDirectories(Path.of("./tmp/" + ports[i]))
            ));
        }
        for (ServiceConfig config : clusterConfs) {
            Service instance = new DaoService(config);
            try {
                instance.start().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                Thread.currentThread().interrupt();
                log.error(e.getMessage());
            }
        }
    }

    private Main() {

    }
}
