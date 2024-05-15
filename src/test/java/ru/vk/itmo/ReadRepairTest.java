package ru.vk.itmo;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReadRepairTest extends TestBase {
    @ServiceTest(stage = 7, clusterSize = 2)
    void getValue(List<ServiceInfo> serviceInfos) throws Exception {
        final String key = randomId();
        byte[] value1 = randomValue();
        byte[] value2 = randomValue();

        assertEquals(HttpURLConnection.HTTP_CREATED, serviceInfos.get(0).upsert(key, value1, 1, 1).statusCode());
        assertEquals(HttpURLConnection.HTTP_CREATED, serviceInfos.get(1).upsert(key, value1, 1, 1).statusCode());

        serviceInfos.get(0).stop();

        assertEquals(HttpURLConnection.HTTP_CREATED, serviceInfos.get(1).upsert(key, value2, 1, 1).statusCode());

        serviceInfos.get(0).start();

        HttpResponse<byte[]> responseService1 = serviceInfos.get(0).get(key);
        HttpResponse<byte[]> responseService2 = serviceInfos.get(1).get(key);

        assertEquals(HttpURLConnection.HTTP_OK, responseService1.statusCode());
        assertEquals(HttpURLConnection.HTTP_OK, responseService2.statusCode());
        assertArrayEquals(responseService1.body(), responseService2.body());

    }
}
