package ru.vk.itmo.test.khadyrovalmasgali.util;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;

import java.io.IOException;

public final class HttpUtil {

    private HttpUtil() {

    }

    public static void sessionSendSafe(HttpSession session, Response r, Logger log) {
        try {
            session.sendResponse(r);
        } catch (IOException e) {
            log.error("IO exception during sending response", e);
            session.scheduleClose();
        }
    }
}
