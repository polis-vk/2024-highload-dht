package ru.vk.itmo.test.dariasupriadkina;

import one.nio.http.Request;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedEntry;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

public class Utils {
    public static final String ENTRY_PREFIX = "/v0/entity";
    public static final String LOCAL_STREAM_ENTRY_PREFIX = "/v0/entities";
    public static final String ENTRY_PREFIX_WITH_ID_PARAM = ENTRY_PREFIX + "?id=";
    private final Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao;

    public Utils(Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao) {
        this.dao = dao;
    }

    public Entry<MemorySegment> convertToEntry(String id, byte[] body) {
        return new BaseEntry<>(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)),
                convertByteArrToMemorySegment(body));
    }

    public MemorySegment convertByteArrToMemorySegment(byte[] bytes) {
        return bytes == null ? null : MemorySegment.ofArray(bytes);
    }

    public ExtendedEntry<MemorySegment> getEntryById(String id) {
        return dao.get(convertByteArrToMemorySegment(id.getBytes(StandardCharsets.UTF_8)));
    }

    public String getEntryUrl(String commonUrl, String id) {
        return commonUrl + ENTRY_PREFIX_WITH_ID_PARAM + id;
    }

    public String getIdParameter(Request request) {
        return request.getParameter("id=");
    }

}
