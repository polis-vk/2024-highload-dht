package ru.vk.itmo.test.smirnovandrew;

import java.io.Serializable;

public record ValWithTime(byte[] value, long timestamp) implements Serializable {
}
