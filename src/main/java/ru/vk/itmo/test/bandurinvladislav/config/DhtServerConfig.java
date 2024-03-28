package ru.vk.itmo.test.bandurinvladislav.config;

import one.nio.http.HttpServerConfig;

import java.util.List;

public class DhtServerConfig extends HttpServerConfig {
    public List<String> clusterUrls;
    public String selfUrl;
}
