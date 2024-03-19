package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.kovalevigor.server.ServerDaoStrategy.log;
import static ru.vk.itmo.test.kovalevigor.server.ServerUtil.shutdownAndAwaitTermination;

public class ServerOneExecutorStrategyDecorator extends ServerStrategyDecorator implements RejectedExecutionHandler {
    private final ThreadPoolExecutor executorService;
    private final BlockingQueue<Runnable> blockingQueue;

    public ServerOneExecutorStrategyDecorator(
            ServerStrategy serverStrategy,
            int corePoolSize, int maximumPoolSize,
            long keepAliveTime, int queueCapacity
    ) {
        super(serverStrategy);
        blockingQueue = new LinkedBlockingQueue<>(queueCapacity);
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

    @Override
    public void handleRequest(Request request, HttpSession session) {
        executorService.execute(new ServerTask(request, session, super::handleRequest));
    }

    @Override
    public void close() throws IOException {
        shutdownAndAwaitTermination(executorService);
        super.close();
    }
}
