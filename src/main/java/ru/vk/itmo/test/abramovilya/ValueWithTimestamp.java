package ru.vk.itmo.test.abramovilya;

import java.io.Serializable;

public record ValueWithTimestamp(byte[] value, long timestamp) implements Serializable {
}
