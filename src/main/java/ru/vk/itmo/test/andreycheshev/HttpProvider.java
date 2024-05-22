package ru.vk.itmo.test.andreycheshev;

public interface HttpProvider {
    ResponseElements get(String id);

    ResponseElements put(String id, byte[] body, long timestamp);

    ResponseElements delete(String id, long timestamp);
}
