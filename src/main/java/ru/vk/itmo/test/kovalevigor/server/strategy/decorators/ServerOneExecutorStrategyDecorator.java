package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.ServerTask;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.shutdownAndAwaitTermination;

public class ServerOneExecutorStrategyDecorator extends ServerStrategyDecorator implements RejectedExecutionHandler {
    private final ThreadPoolExecutor executorService;
    private final BlockingQueue<Runnable> blockingQueue;

    public ServerOneExecutorStrategyDecorator(
            ServerStrategy serverStrategy,
            int corePoolSize, int maximumPoolSize,
            long keepAliveTime, int queueCapacity
    ) {
        super(serverStrategy);
        blockingQueue = new ArrayBlockingQueue<>(queueCapacity);
        executorService = new ThreadPoolExecutor(
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.SECONDS,
                blockingQueue,
                this
        );
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        log.severe("Service are unavailable: queue size " + blockingQueue.size());
        ServerTask serverTask = (ServerTask) r;
        rejectRequest(serverTask.request, serverTask.session);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public Response handleRequest(Request request, HttpSession session) {
        handleRequestAsync(request, session);
        return null;
    }

    @Override
    public CompletableFuture<Response> handleRequestAsync(Request request, HttpSession session) {
        return super.handleRequestAsync(request, session, executorService);
    }

    @Override
    public void close() throws IOException {
        shutdownAndAwaitTermination(executorService);
        super.close();
    }
}
