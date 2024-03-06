package ru.vk.itmo.test.bandurinvladislav.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.bandurinvladislav.util.Constants;

public class DeadlineRunnable implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(DeadlineRunnable.class);

    private final Runnable task;
    private final long creationTime;

    public DeadlineRunnable(Runnable task, long creationTime) {
        this.task = task;
        this.creationTime = creationTime;
    }

    @Override
    public void run() {
        if (System.currentTimeMillis() - creationTime > Constants.TASK_DEADLINE_MILLIS) {
            logger.info("Task dropped due to timeout");
            return;
        }
        task.run();
    }
}
