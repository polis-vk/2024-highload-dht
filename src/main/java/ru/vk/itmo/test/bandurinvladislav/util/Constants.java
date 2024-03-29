package ru.vk.itmo.test.bandurinvladislav.util;

public final class Constants {
    public static final String ENDPOINT = "/v0/entity";
    public static final String PARAMETER_ID = "id=";
    public static final String TOO_MANY_REQUESTS = "429 too many requests";

    public static final int THREADS = Runtime.getRuntime().availableProcessors();
    public static final long TASK_DEADLINE_MILLIS = 500;
    public static final int FLUSH_THRESHOLD_BYTES = 10 * 1024 * 1024;
    public static final int QUEUE_SIZE = 128;

    private Constants() {
    }
}
