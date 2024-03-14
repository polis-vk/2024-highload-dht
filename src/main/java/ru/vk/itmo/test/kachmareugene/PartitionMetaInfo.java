package ru.vk.itmo.test.kachmareugene;

import one.nio.http.Request;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PartitionMetaInfo {
    private final Map<PartitionSegment, Integer> segmentTovnode = new HashMap<>();

    private final Map<Integer, Integer> vnodeToRealNode = new HashMap<>();
    private final List<Integer> nodeToPort = new ArrayList<>();
    private static final int VNODE_LENGTH = 16;

    public int maxKeyHashValue;
    public PartitionMetaInfo(List<String> urls, int nodesFactor) {
        int nodesCount = urls.size();

        for (String url : urls) {
            nodeToPort.add(Integer.parseInt(url.split(":")[2]));
        }

        this.maxKeyHashValue = nodesCount * nodesFactor * VNODE_LENGTH;

        int curStart = 0;
        int curEnd = VNODE_LENGTH;

        for (int vnode = 0, realNode = 0;
             vnode < nodesCount * nodesFactor;
             vnode++, realNode = (realNode + 1) % nodesCount) {

            segmentTovnode.put(new PartitionSegment(curStart, curEnd), vnode);
            vnodeToRealNode.put(vnode, realNode);
            curStart += VNODE_LENGTH;
            curEnd += VNODE_LENGTH;
        }
    }

    public int getRealPortWithDataByRequest(Request request) {
        int requestHash = Math.abs(request.getParameter("id").hashCode() * 5);
        int vNodeSegment = requestHash % maxKeyHashValue;

        for (var partSeg : segmentTovnode.entrySet()) {
            if (partSeg.getKey().start <= vNodeSegment && partSeg.getKey().end > vNodeSegment) {
                return nodeToPort.get(vnodeToRealNode.get(partSeg.getValue()));
            }
        }

        return -1;
    }

    private static class PartitionSegment {
        int start;
        int end;

        public PartitionSegment(int start, int end) {
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PartitionSegment that = (PartitionSegment) o;
            return start == that.start && end == that.end;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }
    }
}
