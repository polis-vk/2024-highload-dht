package ru.vk.itmo.test.proninvalentin.failure_limiter;

import java.util.List;

public record FailureLimiterConfig(int MaxFailureNumber, List<String> clusterUrls) {
    public static FailureLimiterConfig defaultConfig(List<String> clusterUrls) {
        return new FailureLimiterConfig(128, clusterUrls);
    }
}
