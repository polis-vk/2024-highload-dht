package ru.vk.itmo.test.andreycheshev;

public record ParametersTuple<T>(T first, T second) {

    public boolean isValidAckFrom() {
        Integer intFirst = (Integer) first;
        Integer intSecond = (Integer) second;
        return intFirst > 0 && intFirst < intSecond;
    }
}
