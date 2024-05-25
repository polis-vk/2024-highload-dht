package ru.vk.itmo.test.proninvalentin;

public class ServerConfig {
    private final int maxWorkersNumber;

    private final int requestMaxTimeToTakeInWork;

    private final int httpRequestTimeoutInMillis;

    public int getMaxWorkersNumber() {
        return maxWorkersNumber;
    }

    public int getRequestMaxTimeToTakeInWorkInMillis() {
        return requestMaxTimeToTakeInWork;
    }

    public int getHttpRequestTimeoutInMillis() {
        return httpRequestTimeoutInMillis;
    }

    public ServerConfig(int maxWorkersNumber, int requestMaxTimeToTakeInWork, int httpRequestTimeoutInMillis) {
        this.maxWorkersNumber = maxWorkersNumber;
        this.requestMaxTimeToTakeInWork = requestMaxTimeToTakeInWork;
        this.httpRequestTimeoutInMillis = httpRequestTimeoutInMillis;
    }

    public static ServerConfig defaultConfig() {
        int workersNumber = Runtime.getRuntime().availableProcessors() * 2;
        int requestMaxTimeToTakeInWorkInMillis = 300;
        int httpRequestTimeoutInMillis = 200;
        return new ServerConfig(workersNumber, requestMaxTimeToTakeInWorkInMillis, httpRequestTimeoutInMillis);
    }
}
