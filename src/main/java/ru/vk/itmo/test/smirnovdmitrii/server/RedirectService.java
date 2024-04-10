package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RedirectService {

    @DhtValue("cluster.redirect.timeout:100")
    private static int REDIRECT_TIMEOUT;
    private final Map<String, HttpClient> clients = new HashMap<>();
    private static final Logger logger = LoggerFactory.getLogger(RedirectService.class);
    public static final String REDIRECT_HEADER_NAME = "X-REPLICATION-REQUEST:";
    public static final String REDIRECT_HEADER = REDIRECT_HEADER_NAME + " true";

    public RedirectService(final String selfUrl, List<String> clusterUrls) {
        for (final String clusterUrl: clusterUrls) {
            if (clusterUrl.equals(selfUrl)) {
                continue;
            }
            clients.put(clusterUrl, new HttpClient(new ConnectionString(clusterUrl)));
        }
    }

    public Response redirect(
            final String url,
            final Request request
    ) throws HttpException, IOException, InterruptedException {
        final HttpClient client = clients.get(url);
        try {
            logger.trace("sending redirect to node {}", url);
            final Request redirectRequest = client.createRequest(
                    request.getMethod(),
                    request.getURI()
            );
            final byte[] body = request.getBody();
            if (body != null) {
                redirectRequest.setBody(body);
            }
            final String[] headers = request.getHeaders();
            for (int i = 0; i < request.getHeaderCount(); i++) {
                redirectRequest.addHeader(headers[i]);
            }
            redirectRequest.addHeader(REDIRECT_HEADER);
            return client.invoke(redirectRequest, REDIRECT_TIMEOUT);
        } catch (PoolException e) {
            return new Response(Response.BAD_GATEWAY, Response.EMPTY);
        }
    }

    public void close() {
        for (final Map.Entry<String, HttpClient> entry: clients.entrySet()) {
            entry.getValue().close();
        }
    }
}
