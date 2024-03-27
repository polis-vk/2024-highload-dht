package ru.vk.itmo.test.kachmareugene;

import one.nio.http.Request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PartitionMetaInfo {
    private final List<Integer> mappedVnodes;
    private final List<String> urls;
    public final int maxKeyHashValue;

    public PartitionMetaInfo(List<String> urls, int nodesFactor) {
        int nodesCount = urls.size();

        mappedVnodes = new ArrayList<>();
        this.maxKeyHashValue = nodesCount * nodesFactor;

        for (int i = 0; i < maxKeyHashValue; i++) {
            mappedVnodes.add(i % urls.size());
        }
        Collections.shuffle(mappedVnodes, new Random(120));
        this.urls = urls;
    }

    public String getCorrectURL(Request request) {
        int requestHash = Math.abs(request.getParameter("id").hashCode());
        int vnode = requestHash % mappedVnodes.size();

        return urls.get(mappedVnodes.get(vnode));
    }
}
