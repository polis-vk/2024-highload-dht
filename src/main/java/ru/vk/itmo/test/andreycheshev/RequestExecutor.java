package ru.vk.itmo.test.andreycheshev;

import one.nio.async.CompletedFuture;
import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.andreycheshev.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.*;

public class RequestExecutor {
    private static final int KEEPALIVE_MILLIS = 3000;
    private static final int MAX_WORKERS_COUNT = 100;
    private final BlockingQueue<Runnable> workQueue;
    private final ExecutorService workers;
    private final RequestHandler requestHandler;

    public RequestExecutor(ReferenceDao dao) {
        workQueue = new ArrayBlockingQueue<>(MAX_WORKERS_COUNT);
        requestHandler = new RequestHandler(dao);

        int cpuThreadsCount = Runtime.getRuntime().availableProcessors();
        workers = new ThreadPoolExecutor(
                cpuThreadsCount,
                cpuThreadsCount * 10,
                KEEPALIVE_MILLIS,
                TimeUnit.MILLISECONDS,
                workQueue,
                new WorkerThreadFactory(),
                new ThreadExceptionHandler()
        );
    }

    @SuppressWarnings({"FutureReturnValueIgnored", "ThreadPriorityCheck"})
    public void execute(Request request, HttpSession session) {
        Callable<Response> task = new ResponseGeneratorCallable(request, requestHandler);

        CompletableFuture<Response> completableFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return task.call();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                workers
        );

        CompletableFuture.runAsync(() -> {
            while(!completableFuture.isDone()) {
                Thread.yield();
            }
            try {
                session.sendResponse(completableFuture.get());
            } catch (IOException | InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public void shutdown() {
        workers.shutdown();
    }

}
