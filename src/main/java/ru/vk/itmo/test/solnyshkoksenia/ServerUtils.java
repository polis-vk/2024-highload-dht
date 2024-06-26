package ru.vk.itmo.test.solnyshkoksenia;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.Hash;
import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ServerUtils {
    private static final String HEADER_TIMESTAMP = "X-timestamp";
    private static final String HEADER_TIMESTAMP_HEADER = HEADER_TIMESTAMP + ": ";
    protected static MemorySegment toMS(String input) {
        return MemorySegment.ofArray(input.getBytes(StandardCharsets.UTF_8));
    }

    protected static HttpRequest buildHttpRequest(String executorNode, Request request, String uri) {
        return HttpRequest.newBuilder(URI.create(executorNode + uri))
                .method(
                        request.getMethodName(),
                        request.getBody() == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(request.getBody())
                )
                .timeout(Duration.ofMillis(500))
                .build();
    }

    protected static Response makeResponse(HttpResponse<byte[]> httpResponse) {
        Optional<String> header = httpResponse.headers().firstValue(HEADER_TIMESTAMP);
        long timestamp;
        if (header.isPresent()) {
            try {
                timestamp = Long.parseLong(header.get());
            } catch (Exception e) {
                timestamp = 0;
            }
        } else {
            timestamp = 0;
        }
        Response response = new Response(Integer.toString(httpResponse.statusCode()), httpResponse.body());
        response.addHeader(HEADER_TIMESTAMP_HEADER + timestamp);
        return response;
    }

    protected static List<String> getNodesByEntityId(List<String> urls, String id, Integer from) {
        List<Node> executorNodes = new ArrayList<>();

        for (int i = 0; i < from; i++) {
            int hash = Hash.murmur3(urls.get(i) + id);
            executorNodes.add(new Node(i, hash));
            executorNodes.sort(Node::compareTo);
        }

        for (int i = from; i < urls.size(); i++) {
            int hash = Hash.murmur3(urls.get(i) + id);
            if (executorNodes.getFirst().hash < hash) {
                executorNodes.remove(executorNodes.getFirst());
                executorNodes.add(new Node(i, hash));
                executorNodes.sort(Node::compareTo);
                break;
            }
        }

        return executorNodes.stream().map(node -> urls.get(node.id)).toList();
    }

    @SuppressWarnings("unused")
    protected static String printEntry(Entry<MemorySegment> entry) {
        return "Key: " + toString(entry.key()) + " Value: " + toString(entry.value());
    }

    private static String toString(MemorySegment memorySegment) {
        return memorySegment == null ? "" : new String(memorySegment.toArray(ValueLayout.JAVA_BYTE),
                StandardCharsets.UTF_8);
    }


    @SuppressWarnings("unused")
    private record Node(int id, int hash) implements Comparable<Node> {
        @Override
        public int compareTo(Node o) {
            return Integer.compare(hash, o.hash);
        }
    }
}
