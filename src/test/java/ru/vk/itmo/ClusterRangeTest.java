package ru.vk.itmo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.vk.itmo.test.solnyshkoksenia.ServiceImpl;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for cluster node range API.
 *
 * @author Ksenia Solnyshko
 */
class ClusterRangeTest {
    private static final String LOCALHOST_PREFIX = "http://localhost:";
    private static final String DAO_PREFIX = "dao/tmp/test/";
    private static final int CLUSTER_SIZE = 5;
    private static final List<ServiceInfoExtended> services = new ArrayList<>(CLUSTER_SIZE);
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    @SuppressWarnings("FutureReturnValueIgnored")
    @BeforeAll
    public static void before() throws IOException {
        Map<Integer, String> nodes = new HashMap<>();
        int nodePort = 8080;
        for (int i = 0; i < CLUSTER_SIZE; i++) {
            nodes.put(nodePort, LOCALHOST_PREFIX + nodePort);
            nodePort += 10;
        }

        List<String> clusterUrls = new ArrayList<>(nodes.values());
        for (Map.Entry<Integer, String> entry : nodes.entrySet()) {
            int port = entry.getKey();
            String url = entry.getValue();

            Path path = Paths.get(DAO_PREFIX + port);
            Files.createDirectories(path);

            ServiceConfig serviceConfig = new ServiceConfig(port, url, clusterUrls, path);
            ServiceImpl instance = new ServiceImpl(serviceConfig);
            services.add(new ServiceInfoExtended(instance, serviceConfig, HTTP_CLIENT));
            instance.start();
        }
    }

    @AfterAll
    public static void after() throws Exception {
        for (ServiceInfoExtended service : services) {
            service.cleanUp();
        }
    }

    private static byte[] chunkOf(
            String key,
            String value) {
        return (key + '\n' + value).getBytes(StandardCharsets.UTF_8);
    }

    private ServiceInfoExtended getRandomService() {
        return services.get((int) (Math.random() * services.size()));
    }

    @Test
    void getAbsent() throws Exception {
        ServiceInfoExtended service = getRandomService();
        HttpResponse<byte[]> response = service.clusterRange("absent0", "absent1");
        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
        assertEquals(0, response.body().length);
    }

    @Test
    void single() throws Exception {
        var service = getRandomService();
        String prefix = "single";
        String key = prefix + 1;
        String value = "value1";

        // Insert
        assertEquals(HttpURLConnection.HTTP_CREATED, service.upsert(key, value.getBytes(StandardCharsets.UTF_8),
                CLUSTER_SIZE - 2, CLUSTER_SIZE - 2).statusCode());

        // Check
        {
            service = getRandomService();
            HttpResponse<byte[]> response = service.clusterRange(key, prefix + 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(chunkOf(key, value), response.body());
        }

        // Excluding the key
        {
            service = getRandomService();
            HttpResponse<byte[]> response = service.clusterRange("a", key);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertEquals(0, response.body().length);
        }

        // After the key
        {
            service = getRandomService();
            HttpResponse<byte[]> response = service.clusterRange(prefix + 2, prefix + 3);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertEquals(0, response.body().length);
        }
    }

    @Test
    void triple() throws Exception {
        String prefix = "triple";
        String value1 = "value1";
        String value2 = "";
        String value3 = "value3";

        // Insert reversed
        assertEquals(HttpURLConnection.HTTP_CREATED, getRandomService().upsert(prefix + 3,
                value3.getBytes(StandardCharsets.UTF_8), CLUSTER_SIZE - 2, CLUSTER_SIZE - 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_CREATED, getRandomService().upsert(prefix + 2,
                value2.getBytes(StandardCharsets.UTF_8), CLUSTER_SIZE - 2, CLUSTER_SIZE - 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_CREATED, getRandomService().upsert(prefix + 1,
                value1.getBytes(StandardCharsets.UTF_8), CLUSTER_SIZE - 2, CLUSTER_SIZE - 2).statusCode());

        // Check all
        {
            byte[] chunk1 = chunkOf(prefix + 1, value1);
            byte[] chunk2 = chunkOf(prefix + 2, value2);
            byte[] chunk3 = chunkOf(prefix + 3, value3);
            byte[] expected = new byte[chunk1.length + chunk2.length + chunk3.length];
            System.arraycopy(chunk1, 0, expected, 0, chunk1.length);
            System.arraycopy(chunk2, 0, expected, chunk1.length, chunk2.length);
            System.arraycopy(chunk3, 0, expected, expected.length - chunk3.length, chunk3.length);

            HttpResponse<byte[]> response = getRandomService().clusterRange(prefix + 1, prefix + 4);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(expected, response.body());
        }

        // To the left
        {
            HttpResponse<byte[]> response = getRandomService().clusterRange(prefix + 0, prefix + 1);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertEquals(0, response.body().length);
        }

        // First left
        {
            HttpResponse<byte[]> response = getRandomService().clusterRange(prefix + 0, prefix + 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(chunkOf(prefix + 1, value1), response.body());
        }

        // First point
        {
            HttpResponse<byte[]> response = getRandomService().clusterRange(prefix + 1, prefix + 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(chunkOf(prefix + 1, value1), response.body());
        }

        // Second point
        {
            HttpResponse<byte[]> response = getRandomService().clusterRange(prefix + 2, prefix + 3);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(chunkOf(prefix + 2, value2), response.body());
        }

        // Third point
        {
            HttpResponse<byte[]> response = getRandomService().clusterRange(prefix + 3, prefix + 4);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(chunkOf(prefix + 3, value3), response.body());
        }

        // To the right
        {
            HttpResponse<byte[]> response = getRandomService().clusterRange(prefix + 4, null);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertEquals(0, response.body().length);
        }
    }
}
