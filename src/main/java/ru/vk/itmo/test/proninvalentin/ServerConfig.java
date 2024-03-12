package ru.vk.itmo.test.proninvalentin;

public class ServerConfig {
    private final int maxWorkersNumber;

    private final int requestMaxProcessingTimeInMilliseconds;

    public int getMaxWorkersNumber() {
        return maxWorkersNumber;
    }

    public int getRequestTimeoutInMilliseconds() {
        return requestMaxProcessingTimeInMilliseconds;
    }

    public ServerConfig(int maxWorkersNumber, int requestMaxProcessingTimeInMilliseconds) {
        this.maxWorkersNumber = maxWorkersNumber;
        this.requestMaxProcessingTimeInMilliseconds = requestMaxProcessingTimeInMilliseconds;
    }

    public static ServerConfig defaultConfig() {
        int workersNumber = Runtime.getRuntime().availableProcessors() * 2;
        int requestMaxProcessingTimeInMilliseconds = 200;
        return new ServerConfig(workersNumber, requestMaxProcessingTimeInMilliseconds);
    }
}
