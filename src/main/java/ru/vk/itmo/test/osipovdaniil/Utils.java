package ru.vk.itmo.test.osipovdaniil;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;

import java.io.IOException;

public final class Utils {

    private Utils() {
    }

    public static void sendEmptyInternal(final HttpSession session, final Logger logger) {
        try {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException e) {
            logger.error("Exception while sending close connection", e);
            session.scheduleClose();
        }
    }
}
