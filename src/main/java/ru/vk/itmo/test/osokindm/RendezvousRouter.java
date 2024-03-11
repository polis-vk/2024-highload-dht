package ru.vk.itmo.test.osokindm;

import one.nio.util.Hash;

import java.util.HashMap;
import java.util.Map;
import ru.vk.itmo.test.osokindm.ClusterNode.Node;
import ru.vk.itmo.test.osokindm.ClusterNode.VirtualNode;


public class RendezvousRouter {

    private static final int VNODES_PER_NODE = 10;
    private Map<Integer, ClusterNode.VirtualNode> shards = new HashMap<>();


    public void addNode(Node node) {
        for (int i = 0; i < VNODES_PER_NODE; i++) {
            // здесь мы добавляем ноду, на каждую ноду должна быть виртуальная нода
            int hash = Hash.murmur3(node.address + i);
            VirtualNode vNode = new VirtualNode(i);
            // обработать возможное появление коллизии
            shards.put(hash, vNode);

        }
    }

    public String getNode(String data) {
        int max = 0;
        for (int i = 0; i < shards.keySet().size(); i++) {

            int hash = Hash.murmur3(shards.get(i) + data);
            if (hash > max) {
                max = hash;
            }
        }
        // вычисляем хэш функцию для каждой ноды, берем максимальную, добавляем в нужный кластер
        // вычислив, в какую ноду мы хотим добавить элемент, мы берем адрес этой ноды и возвращаем его
        // сервер уже делает запрос по этому адресу
        return null;
    }


}
