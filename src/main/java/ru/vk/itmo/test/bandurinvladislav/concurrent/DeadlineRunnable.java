package ru.vk.itmo.test.bandurinvladislav.concurrent;

import ru.vk.itmo.test.bandurinvladislav.util.Constants;

public class DeadlineRunnable implements Runnable {
    private final Runnable task;
    private final long creationTime;

    public DeadlineRunnable(Runnable task, long creationTime) {
        this.task = task;
        this.creationTime = creationTime;
    }

    @Override
    public void run() {
        if (System.currentTimeMillis() - creationTime > Constants.TASK_DEADLINE_MILLIS) {
            return;
        }
        task.run();
    }
}
