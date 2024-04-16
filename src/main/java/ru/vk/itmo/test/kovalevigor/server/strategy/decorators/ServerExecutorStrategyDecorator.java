package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.server.SelectorThread;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerFull;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.ServerTask;
import ru.vk.itmo.test.kovalevigor.server.util.ServerUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy.log;
import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.shutdownAndAwaitTermination;

public class ServerExecutorStrategyDecorator extends ServerStrategyDecorator implements RejectedExecutionHandler {
    private final ThreadPoolExecutor mainExecutor;
    private final Map<Thread, ThreadPoolExecutor> executors;
    private final long keepAliveTime;
    private static final int SUB_EXECUTORS_POOL_SIZE = 1;

    public ServerExecutorStrategyDecorator(
            ServerStrategy serverStrategy,
            int corePoolSize, int maximumPoolSize,
            long keepAliveTime, int queueCapacity
    ) {
        super(serverStrategy);
        this.executors = new HashMap<>();
        this.keepAliveTime = keepAliveTime;
        int realCorePoolSize = corePoolSize == 1 ? 1 : corePoolSize / 2;
        this.mainExecutor = new ThreadPoolExecutor(
                realCorePoolSize,
                maximumPoolSize - realCorePoolSize,
                this.keepAliveTime,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity * realCorePoolSize),
                new CustomPolicy()
        );
        this.mainExecutor.prestartAllCoreThreads();
    }

    private class CustomPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.severe("Service are unavailable: queue size " + executor.getQueue().size());
            ServerTask serverTask = (ServerTask) r;
            rejectRequest(serverTask.request, serverTask.session);
        }
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        int size = executor.getQueue().size() / 2;
        List<Runnable> notExecuted = new ArrayList<>(size);
        executor.getQueue().drainTo(notExecuted, size);
        notExecuted.forEach(mainExecutor::execute);
        executor.execute(r);
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) {
        executors.get(Thread.currentThread()).execute(new ServerTask(request, session, super::handleRequest));
        return null;
    }

    @Override
    public void close() throws IOException {
        shutdownAndAwaitTermination(mainExecutor);
        executors.values().forEach(ServerUtil::shutdownAndAwaitTermination);
        super.close();
    }

    @Override
    public void start(ServerFull httpServer) {
        ThreadPoolExecutor[] threadPoolExecutors = new ThreadPoolExecutor[mainExecutor.getCorePoolSize()];
        for (int i = 0; i < threadPoolExecutors.length; i++) {
            threadPoolExecutors[i] = new ThreadPoolExecutor(
                    SUB_EXECUTORS_POOL_SIZE,
                    SUB_EXECUTORS_POOL_SIZE,
                    this.keepAliveTime,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(
                            mainExecutor.getQueue().remainingCapacity() / mainExecutor.getCorePoolSize()
                    ),
                    this
            );
        }
        SelectorThread[] selectors = httpServer.getSelectors();
        for (int i = 0; i < selectors.length; i++) {
            executors.put(selectors[i], threadPoolExecutors[i % threadPoolExecutors.length]);
        }
    }
}
