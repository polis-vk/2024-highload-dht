package ru.vk.itmo.test.trofimovmaxim;

import one.nio.http.Response;

import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.function.BiConsumer;

import static ru.vk.itmo.test.trofimovmaxim.TrofikServer.HEADER_TIMESTAMP;

public class ResponseProcessor implements BiConsumer<HttpResponse<byte[]>, Throwable> {
    MergeHandleResult mergeHandleResult;
    int index;

    public ResponseProcessor(MergeHandleResult mergeHandleResult, int index) {
        this.mergeHandleResult = mergeHandleResult;
        this.index = index;
    }

    public void processResult(HandleResult result) {
        mergeHandleResult.add(index, result);
    }

    @Override
    public void accept(HttpResponse<byte[]> httpResponse, Throwable throwable) {
        if (throwable != null) {
            mergeHandleResult.add(index, new HandleResult(HttpURLConnection.HTTP_INTERNAL_ERROR, Response.EMPTY));
            return;
        }
        Optional<String> string = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
        long timestamp;
        if (string.isPresent()) {
            try {
                timestamp = Long.parseLong(string.get());
            } catch (Exception e) {
                timestamp = 0;
            }
        } else {
            timestamp = 0;
        }

        mergeHandleResult.add(index, new HandleResult(httpResponse.statusCode(), httpResponse.body(), timestamp));
    }
}
