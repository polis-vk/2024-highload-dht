package ru.vk.itmo;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ReadRepairTest extends TestBase {
    @ServiceTest(stage = 7, clusterSize = 2)
    void getValue(List<ServiceInfo> serviceInfos) throws Exception {
        final String key = randomId();
        byte[] value1 = randomValue();
        byte[] value2 = randomValue();

        serviceInfos.get(0).stop();

        assertEquals(HttpURLConnection.HTTP_CREATED, serviceInfos.get(1).upsert(key, value1, 1, 1).statusCode());
        HttpResponse<byte[]> responseService1 = serviceInfos.get(1).get(key, 1, 1);

        serviceInfos.get(0).start();

        serviceInfos.get(1).stop();

        assertEquals(HttpURLConnection.HTTP_CREATED, serviceInfos.get(0).upsert(key, value2, 1, 1).statusCode());
        HttpResponse<byte[]> responseService0 = serviceInfos.get(0).get(key, 1,1);

        serviceInfos.get(1).start();

        assertFalse(Arrays.equals(responseService0.body(), responseService1.body()));

        // Обе ноды подняты. Должно обнаружиться расхождение и сработать обновление данных
        responseService1 = serviceInfos.get(1).get(key, 1,1);

        serviceInfos.get(1).stop();

        responseService0 = serviceInfos.get(0).get(key, 1,1);
        assertEquals(HttpURLConnection.HTTP_OK, responseService1.statusCode());
        assertArrayEquals(responseService1.body(), responseService0.body());
    }
}
