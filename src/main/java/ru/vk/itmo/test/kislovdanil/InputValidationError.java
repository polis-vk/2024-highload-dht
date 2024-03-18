package ru.vk.itmo.test.kislovdanil;

public class InputValidationError extends RuntimeException {
    public InputValidationError(String s) {
        super(s);
    }
}
