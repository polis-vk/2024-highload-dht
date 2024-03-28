package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.LSMDaoOutOfMemoryException;
import ru.vk.itmo.test.viktorkorotkikh.dao.exceptions.TooManyFlushesException;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMConstantResponse;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMCustomSession;
import ru.vk.itmo.test.viktorkorotkikh.http.LSMServerResponseWithMemorySegment;
import ru.vk.itmo.test.viktorkorotkikh.http.OneNioHttpResponseWrapper;
import ru.vk.itmo.test.viktorkorotkikh.http.ReplicaEmptyResponse;
import ru.vk.itmo.test.viktorkorotkikh.util.LsmServerUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;
import static ru.vk.itmo.test.viktorkorotkikh.util.LsmServerUtil.timestampToHeader;

public class LSMServerImpl extends HttpServer {
    private static final Logger log = LoggerFactory.getLogger(LSMServerImpl.class);
    private static final String ENTITY_V0_PATH_WITH_ID_PARAM = "/v0/entity?id=";
    private static final int CLUSTER_REQUEST_TIMEOUT_MILLISECONDS = 1_000;
    private static final Set<Integer> ALLOWED_METHODS = Set.of(METHOD_GET, METHOD_PUT, METHOD_DELETE);
    private static final String REPLICA_REQUEST_HEADER = "X-Replica-Request";
    private final Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao;
    private final ExecutorService executorService;
    private final ConsistentHashingManager consistentHashingManager;
    private final String selfUrl;
    private final HttpClient clusterClient;
    private final ServiceConfig serviceConfig;

    public LSMServerImpl(
            ServiceConfig serviceConfig,
            Dao<MemorySegment, TimestampedEntry<MemorySegment>> dao,
            ExecutorService executorService,
            ConsistentHashingManager consistentHashingManager,
            HttpClient clusterClient
    ) throws IOException {
        super(createServerConfig(serviceConfig));
        this.dao = dao;
        this.executorService = executorService;
        this.consistentHashingManager = consistentHashingManager;
        this.selfUrl = serviceConfig.selfUrl();
        this.clusterClient = clusterClient;
        this.serviceConfig = serviceConfig;
    }

    private static HttpServerConfig createServerConfig(ServiceConfig serviceConfig) {
        HttpServerConfig serverConfig = new HttpServerConfig();
        serverConfig.acceptors = new AcceptorConfig[]{createAcceptorConfig(serviceConfig.selfPort())};
        serverConfig.closeSessions = true;
        return serverConfig;
    }

    private static AcceptorConfig createAcceptorConfig(int port) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = port;
        acceptorConfig.reusePort = true;
        return acceptorConfig;
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        try {
            executorService.execute(() -> {
                try {
                    final String path = request.getPath();
                    if (path.startsWith("/v0/entity")) {
                        handleEntityRequest(request, session);
                    } else {
                        super.handleRequest(request, session);
                    }
                } catch (Exception e) {
                    log.error("Unexpected error occurred: ", e);
                    sendResponseAndCloseSessionOnError(session, LSMConstantResponse.SERVICE_UNAVAILABLE_CLOSE);
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("Request was rejected");
            session.sendResponse(LSMConstantResponse.tooManyRequests(request));
        }
    }

    private static void sendResponseAndCloseSessionOnError(final HttpSession session, final Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException ex) {
            log.error("I/O error occurred when sending response");
            session.scheduleClose();
        }
    }

    @Path("/v0/entity")
    public void handleEntityRequest(Request request, HttpSession session) throws IOException {
        if (!ALLOWED_METHODS.contains(request.getMethod())) {
            session.sendResponse(LSMConstantResponse.methodNotAllowed(request));
            return;
        }
        if (request.getMethod() == METHOD_PUT && request.getBody() == null) {
            log.info("PUT bad request: empty body");
            session.sendResponse(LSMConstantResponse.badRequest(request));
            return;
        }

        if (request.getHeader(REPLICA_REQUEST_HEADER) != null) {
            final String id = request.getParameter("id=");
            final byte[] key = id.getBytes(StandardCharsets.UTF_8);
            session.sendResponse(processLocal(request, key, id).getOriginal());
            return;
        }

        RequestParameters requestParameters;
        try {
            requestParameters = getRequestParameters(request, session);
        } catch (NumberFormatException e) {
            log.info("Bad request: invalid parameters");
            session.sendResponse(LSMConstantResponse.badRequest(request));
            return;
        }
        // validate id parameter
        if (requestParameters.id == null || requestParameters.id.isEmpty()) {
            log.info("Bad request: empty id parameter");
            session.sendResponse(LSMConstantResponse.badRequest(request));
            return;
        }
        Integer ack = requestParameters.ack;
        Integer from = requestParameters.from;
        if (ack == null ^ from == null) {
            log.info("Bad request: one of the ack or from parameters is missing");
            session.sendResponse(LSMConstantResponse.badRequest(request));
            return;
        }

        if (ack == null && from == null) {
            from = serviceConfig.clusterUrls().size();
            ack = quorum(serviceConfig.clusterUrls().size());
        }

        if (ack == 0 || from == 0 || ack > from || from > serviceConfig.clusterUrls().size()) {
            log.info("Bad request: ack should be <= from and from should be <= cluster size");
            session.sendResponse(LSMConstantResponse.badRequest(request));
            return;
        }

        final byte[] key = requestParameters.id().getBytes(StandardCharsets.UTF_8);

        final List<String> replicas = getReplicasList(key, requestParameters.from());

        final Response response = getResponseFromReplicas(request, requestParameters.from(), replicas, key, requestParameters.id(), requestParameters.ack());
        session.sendResponse(response);
    }

    private RequestParameters getRequestParameters(Request request, HttpSession session) throws IOException {
        final Iterator<Map.Entry<String, String>> parametersIterator = request.getParameters().iterator();
        String id = null;
        Integer ack = null;
        Integer from = null;
        while (parametersIterator.hasNext() && (id == null || ack == null || from == null)) {
            final Map.Entry<String, String> parameter = parametersIterator.next();
            if (id == null && parameter.getKey().equals("id")) { // get first value
                id = parameter.getValue();
            }

            if (ack == null && parameter.getKey().equals("ack")) { // get first value
                ack = Integer.parseInt(parameter.getValue());
            }

            if (from == null && parameter.getKey().equals("from")) { // get first value
                from = Integer.parseInt(parameter.getValue());
            }
        }

        return new RequestParameters(id, ack, from);
    }

    private record RequestParameters(String id, Integer ack, Integer from) {
    }

    private Response getResponseFromReplicas(Request request, Integer from, List<String> replicas, byte[] key, String id, Integer ack) {
        final List<HttpResponse<byte[]>> responses = new ArrayList<>(from);
        for (final String replicaUrl : replicas) {
            if (replicaUrl.equals(selfUrl)) {
                responses.add(processLocal(request, key, id));
            } else {
                responses.add(processRemote(request, replicaUrl, id));
            }
        }
        return LsmServerUtil.mergeReplicasResponses(request, responses, ack);
    }

    private List<String> getReplicasList(byte[] key, Integer from) {
        final String server = consistentHashingManager.getServerByKey(key);
        final List<String> replicas = new ArrayList<>(from);
        replicas.add(server);
        int i = 0;
        int serverIndex = 0;
        while (serverIndex < serviceConfig.clusterUrls().size() && i < from - 1) {
            final String replica = serviceConfig.clusterUrls().get(serverIndex);
            if (!replica.equals(server)) {
                replicas.add(replica);
                i++;
            }
            serverIndex++;
        }
        return replicas;
    }

    private HttpResponse<byte[]> processRemote(
            final Request originalRequest,
            final String server,
            final String id
    ) {
        final HttpRequest request = createClusterRequest(originalRequest, server, id);
        return sendClusterRequest(request);
    }

    private static HttpRequest createClusterRequest(
            final Request originalRequest,
            final String server,
            final String id
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(server + ENTITY_V0_PATH_WITH_ID_PARAM + id))
                .header(REPLICA_REQUEST_HEADER, "");
        switch (originalRequest.getMethod()) {
            case METHOD_GET -> builder.GET();
            case METHOD_PUT -> {
                final byte[] body = originalRequest.getBody();
                if (body == null) {
                    throw new IllegalStateException("Put doesn't allow empty body.");
                }
                builder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
            }
            case METHOD_DELETE -> builder.DELETE();
            default -> throw new IllegalStateException("Unsupported method " + originalRequest.getMethod());
        }
        return builder.build();
    }

    private HttpResponse<byte[]> sendClusterRequest(
            final HttpRequest request
    ) {
        try {
            return clusterClient
                    .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .get(CLUSTER_REQUEST_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            final String clusterUrl = request.uri().toString();
            Thread.currentThread().interrupt();
            log.warn("Current thread was interrupted during processing request to cluster {}", clusterUrl);
            return new ReplicaEmptyResponse(HTTP_UNAVAILABLE);
        } catch (ExecutionException e) {
            final String clusterUrl = request.uri().toString();
            log.error("Unexpected exception occurred while sending request to cluster {}", clusterUrl, e);
            return new ReplicaEmptyResponse(HTTP_UNAVAILABLE);
        } catch (TimeoutException e) {
            final String clusterUrl = request.uri().toString();
            log.warn("Request timeout to cluster server {}", clusterUrl);
            return new ReplicaEmptyResponse(HTTP_GATEWAY_TIMEOUT);
        }
    }

    private OneNioHttpResponseWrapper processLocal(final Request request, final byte[] key, final String id) {
        Response response = switch (request.getMethod()) {
            case METHOD_GET -> handleGetEntity(request, key, id);
            case METHOD_PUT -> handlePutEntity(request, key, id);
            case METHOD_DELETE -> handleDeleteEntity(request, key, id);
            default -> LSMConstantResponse.methodNotAllowed(request);
        };
        return new OneNioHttpResponseWrapper(response);
    }

    private Response handleGetEntity(final Request request, final byte[] id, final String idString) {
        final TimestampedEntry<MemorySegment> entry;
        try {
            entry = dao.get(fromByteArray(id));
        } catch (UncheckedIOException e) {
            // sstable get method throws UncheckedIOException
            log.error("Unexpected UncheckedIOException occurred", e);
            return LSMConstantResponse.serviceUnavailable(request);
        }
        if (entry == null) {
            log.info("Entity(id={}) was not found", idString);
            return LSMConstantResponse.notFound(request);
        }
        if (entry.value() == null) {
            log.info("Entity(id={}) was deleted", idString);
            Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
            response.addHeader(timestampToHeader(entry.timestamp()));
            return response;
        }

        return new LSMServerResponseWithMemorySegment(Response.OK, entry.value(), timestampToHeader(entry.timestamp()));
    }

    private Response handlePutEntity(final Request request, final byte[] id, final String idString) {
        if (request.getBody() == null) {
            log.info("PUT bad request: empty body");
            return LSMConstantResponse.badRequest(request);
        }

        TimestampedEntry<MemorySegment> newEntry = new TimestampedEntry<>(
                fromByteArray(id),
                MemorySegment.ofArray(request.getBody()),
                Instant.now().toEpochMilli()
        );
        try {
            dao.upsert(newEntry);
        } catch (LSMDaoOutOfMemoryException e) {
            // when entry is too big to be putted into memtable
            log.info("Entity(id={}) is too big to be putted into memtable", idString);
            return LSMConstantResponse.entityTooLarge(request);
        } catch (TooManyFlushesException e) {
            // when one memory table is in the process of being flushed, and the second is already full
            log.warn("Too many flushes");
            return LSMConstantResponse.tooManyRequests(request);
        }

        return LSMConstantResponse.created(request);
    }

    private Response handleDeleteEntity(final Request request, final byte[] id, final String idString) {
        final TimestampedEntry<MemorySegment> newEntry = new TimestampedEntry<>(
                fromByteArray(id),
                null,
                Instant.now().toEpochMilli()
        );
        try {
            dao.upsert(newEntry);
        } catch (LSMDaoOutOfMemoryException e) {
            // when entry is too big to be putted into memtable
            log.info("Entity(id={}) is too big to be putted into memtable", idString);
            return LSMConstantResponse.entityTooLarge(request);
        } catch (TooManyFlushesException e) {
            // when one memory table is in the process of being flushed, and the second is already full
            log.warn("Too many flushes");
            return LSMConstantResponse.tooManyRequests(request);
        }

        return LSMConstantResponse.accepted(request);
    }

    @Path("/v0/compact")
    @RequestMethod(value = {METHOD_GET})
    public Response handleCompact(Request request) throws IOException {
        dao.compact();
        return LSMConstantResponse.ok(request);
    }

    private static MemorySegment fromByteArray(final byte[] data) {
        return MemorySegment.ofArray(data);
    }

    @Override
    public void handleDefault(Request request, HttpSession session) throws IOException {
        session.sendResponse(LSMConstantResponse.badRequest(request));
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new LSMCustomSession(socket, this);
    }

    private static int quorum(final int clusterSize) {
        if (clusterSize < 3) return clusterSize;
        return clusterSize / 2 + 1;
    }
}
