package ru.vk.itmo.test.osokindm;

public class ClusterNode {


    public class Node {
        public final String address;
        public final String name;

        Node(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    public static class VirtualNode {

        public final String name;
        private Node node;
        
        public VirtualNode(int name) {
            this.name = name;
        }

        public void setNode(Node node) {
            this.node = node;
        }

        public Node getNode() {
            return node;
        }

    }
}
