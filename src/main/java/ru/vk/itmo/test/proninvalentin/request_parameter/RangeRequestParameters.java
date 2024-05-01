package ru.vk.itmo.test.proninvalentin.request_parameter;

import ru.vk.itmo.test.proninvalentin.utils.MemorySegmentFactory;
import ru.vk.itmo.test.proninvalentin.utils.Utils;

import java.lang.foreign.MemorySegment;

public class RangeRequestParameters {
    private final MemorySegment start;
    private MemorySegment end;
    private boolean isValid = true;

    public RangeRequestParameters(String startString, String endString) {
        if (Utils.isNullOrBlank(startString)) {
            isValid = false;
            start = null;
            return;
        }

        start = MemorySegmentFactory.fromString(startString);
        if (endString != null) {
            end = MemorySegmentFactory.fromString(endString);
        }
    }

    public boolean isValid() {
        return isValid;
    }

    public MemorySegment start() {
        return start;
    }

    public MemorySegment end() {
        return end;
    }
}
