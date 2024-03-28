package ru.vk.itmo.test.viktorkorotkikh.util;

import java.util.function.BiPredicate;

public final class AcceptAllBiPredicate implements BiPredicate<String, String> {
    public static final AcceptAllBiPredicate INSTANCE = new AcceptAllBiPredicate();

    @Override
    public boolean test(String s, String s2) {
        return true;
    }
}
