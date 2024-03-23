package ru.vk.itmo;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for a two node replicated {@link Service} cluster.
 *
 * @author Vadim Tsesko
 */
class TwoNodeTest extends TestBase {

    @ServiceTest(stage = 4, clusterSize = 2)
    void tooSmallRF(List<ServiceInfo> nodes) throws Exception {
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, nodes.getFirst().get(randomId(), 0, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, nodes.getFirst().upsert(randomId(), randomValue(), 0, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, nodes.getFirst().delete(randomId(), 0, 2).statusCode());
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void tooBigRF(List<ServiceInfo> nodes) throws Exception {
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, nodes.getFirst().get(randomId(), 3, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, nodes.getFirst().upsert(randomId(), randomValue(), 3, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, nodes.getFirst().delete(randomId(), 3, 2).statusCode());
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void unreachableRF(List<ServiceInfo> nodes) throws Exception {
        nodes.get(0).stop();

        assertEquals(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, nodes.get(1).get(randomId(), 2, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, nodes.get(1).upsert(randomId(), randomValue(), 2, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_GATEWAY_TIMEOUT, nodes.get(1).delete(randomId(), 2, 2).statusCode());
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void overlapRead(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();

        // Insert value1
        byte[] value1 = randomValue();
        assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(0).upsert(key, value1, 1, 2).statusCode());

        // Check
        {
            HttpResponse<byte[]> response = nodes.getFirst().get(key, 2, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value1, response.body());
        }
        {
            HttpResponse<byte[]> response = nodes.get(1).get(key, 2, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value1, response.body());
        }

        // Help implementors with ms precision for conflict resolution
        waitForVersionAdvancement();

        // Insert value2
        byte[] value2 = randomValue();
        assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(1).upsert(key, value2, 1, 2).statusCode());

        // Check
        {
            HttpResponse<byte[]> response = nodes.getFirst().get(key, 2, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value2, response.body());
        }
        {
            HttpResponse<byte[]> response = nodes.get(1).get(key, 2, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value2, response.body());
        }
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void overlapWrite(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();

        // Insert value1
        byte[] value1 = randomValue();
        assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(0).upsert(key, value1, 2, 2).statusCode());

        // Check
        {
            HttpResponse<byte[]> response = nodes.getFirst().get(key, 1, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value1, response.body());
        }
        {
            HttpResponse<byte[]> response = nodes.get(1).get(key, 1, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value1, response.body());
        }

        // Help implementors with ms precision for conflict resolution
        waitForVersionAdvancement();

        // Insert value2
        byte[] value2 = randomValue();
        assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(1).upsert(key, value2, 2, 2).statusCode());

        // Check
        {
            HttpResponse<byte[]> response = nodes.getFirst().get(key, 1, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value2, response.body());
        }
        {
            HttpResponse<byte[]> response = nodes.get(1).get(key, 1, 2);
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
            assertArrayEquals(value2, response.body());
        }
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void overlapDelete(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();
        byte[] value = randomValue();

        // Insert & delete at 0
        assertEquals(HttpURLConnection.HTTP_CREATED, nodes.getFirst().upsert(key, value, 2, 2).statusCode());
        waitForVersionAdvancement();
        assertEquals(HttpURLConnection.HTTP_ACCEPTED, nodes.get(0).delete(key, 2, 2).statusCode());

        // Check
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, nodes.get(0).get(key, 1, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, nodes.get(1).get(key, 1, 2).statusCode());

        // Insert & delete at 1
        assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(1).upsert(key, value, 2, 2).statusCode());
        waitForVersionAdvancement();
        assertEquals(HttpURLConnection.HTTP_ACCEPTED, nodes.get(1).delete(key, 2, 2).statusCode());

        // Check
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, nodes.get(0).get(key, 1, 2).statusCode());
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, nodes.get(1).get(key, 1, 2).statusCode());
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void missedWrite(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();

        for (int i = 0; i < nodes.size(); i++) {
            // Stop node
            nodes.get(i).stop();

            // Insert
            byte[] value = randomValue();
            int status = nodes.get((i + 1) % nodes.size()).upsert(key, value, 1, 2).statusCode();
            assertEquals(HttpURLConnection.HTTP_CREATED, status);

            // Start node
            nodes.get(i).start();

            // Check
            {
                HttpResponse<byte[]> response = nodes.get(i).get(key, 2, 2);
                assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
                assertArrayEquals(value, response.body());
            }

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();
        }
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void missedDelete(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();

        for (int i = 0; i < nodes.size(); i++) {
            // Insert
            byte[] value = randomValue();
            assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(i).upsert(key, value, 2, 2).statusCode());

            // Stop node
            nodes.get(i).stop();

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();

            // Delete
            int statusCode = nodes.get((i + 1) % nodes.size()).delete(key, 1, 2).statusCode();
            assertEquals(HttpURLConnection.HTTP_ACCEPTED, statusCode);

            // Start node
            nodes.get(i).start();

            // Check
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, nodes.get(i).get(key, 2, 2).statusCode());

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();
        }
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void missedOverwrite(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();

        for (int i = 0; i < nodes.size(); i++) {
            // Insert value1
            byte[] value1 = randomValue();
            assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(i).upsert(key, value1, 2, 2).statusCode());

            // Stop node
            nodes.get(i).stop();

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();

            // Insert value2
            byte[] value2 = randomValue();
            int status = nodes.get((i + 1) % nodes.size()).upsert(key, value2, 1, 2).statusCode();
            assertEquals(HttpURLConnection.HTTP_CREATED, status);

            // Start node
            nodes.get(i).start();

            // Check value2
            {
                HttpResponse<byte[]> response = nodes.get(i).get(key, 2, 2);
                assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
                assertArrayEquals(value2, response.body());
            }

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();
        }
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void missedRecreate(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();

        for (int i = 0; i < nodes.size(); i++) {

            // Insert & delete value1
            byte[] value1 = randomValue();
            assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(i).upsert(key, value1, 2, 2).statusCode());
            waitForVersionAdvancement();
            assertEquals(HttpURLConnection.HTTP_ACCEPTED, nodes.get(i).delete(key, 2, 2).statusCode());

            // Stop node
            nodes.get(i).stop();

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();

            // Insert value2
            byte[] value2 = randomValue();
            // nodes.get(i) -> nodes.get((i + 1) % nodes.size()) because we stopped i node
            // and if we try to connect this node as main node, but it already stopped
            assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get((i + 1) % nodes.size()).upsert(key, value2, 1, 2).statusCode());

            // Start node
            nodes.get(i).start();

            // Check value2
            {
                HttpResponse<byte[]> response = nodes.get(i).get(key, 2, 2);
                assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
                assertArrayEquals(value2, response.body());
            }

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();
        }
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void tolerateFailure(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();

        for (int i = 0; i < nodes.size(); i++) {

            // Insert into node
            byte[] value = randomValue();
            assertEquals(HttpURLConnection.HTTP_CREATED, nodes.get(i).upsert(key, value, 2, 2).statusCode());

            // Stop node
            nodes.get(i).stop();

            // Check
            {
                // nodes.get(i) -> nodes.get((i + 1) % nodes.size()) because we stopped i node
                // and if we try to connect this node as main node, but it already stopped
                HttpResponse<byte[]> response = nodes.get((i + 1) % nodes.size()).get(key, 1, 2);
                assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
                assertArrayEquals(value, response.body());
            }

            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();

            // Delete
            assertEquals(HttpURLConnection.HTTP_ACCEPTED, nodes.get((i + 1) % nodes.size()).delete(key, 1, 2).statusCode());

            // Check
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, nodes.get((i + 1) % nodes.size()).get(key, 1, 2).statusCode());

            nodes.get(i).start();
            // Help implementors with ms precision for conflict resolution
            waitForVersionAdvancement();
        }
    }

    @ServiceTest(stage = 4, clusterSize = 2)
    void respectRF(List<ServiceInfo> nodes) throws Exception {
        String key = randomId();
        byte[] value = randomValue();

        // Insert
        assertEquals(HttpURLConnection.HTTP_CREATED, nodes.getFirst().upsert(key, value, 1, 1).statusCode());

        // Stop all
        for (ServiceInfo node : nodes) {
            node.stop();
        }

        // Check each
        int successCount = 0;
        // Check each
        for (ServiceInfo serviceInfo : nodes) {
            serviceInfo.start();

            HttpResponse<byte[]> response = serviceInfo.get(key, 1, 1);
            if (response.statusCode() == HttpURLConnection.HTTP_OK && Arrays.equals(value, response.body())) {
                successCount++;
            }

            serviceInfo.stop();
        }
        assertEquals(1, successCount);
    }

}
