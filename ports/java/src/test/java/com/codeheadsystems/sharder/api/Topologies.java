package com.codeheadsystems.sharder.api;

import java.nio.charset.StandardCharsets;

/** The topology documents the public surface tests route against. */
final class Topologies {

    private Topologies() {
    }

    /** A ring document over {@code nodes} nodes, at one token count and one replication factor. */
    static byte[] ring(String topologyId, long epoch, int nodes, int tokensPerWeightUnit,
                       int factor) {
        StringBuilder json = new StringBuilder();
        json.append("{\"formatVersion\":\"1.0\",\"topologyId\":\"").append(topologyId)
                .append("\",\"epoch\":").append(epoch)
                .append(",\"replication\":{\"factor\":").append(factor)
                .append("},\"strategy\":{\"kind\":\"ring\",\"tokenAssignment\":\"derived\"")
                .append(",\"tokensPerWeightUnit\":").append(tokensPerWeightUnit)
                .append(",\"maxTokensPerNode\":4096},\"nodes\":[");
        for (int index = 0; index < nodes; index++) {
            json.append(index == 0 ? "" : ",")
                    .append("{\"id\":\"n").append(index).append("\",\"weight\":1}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A rendezvous document whose summed virtual node count is {@code nodes * perWeightUnit}. */
    static byte[] rendezvous(String topologyId, long epoch, int nodes, int perWeightUnit,
                             int factor) {
        StringBuilder json = new StringBuilder();
        json.append("{\"formatVersion\":\"1.0\",\"topologyId\":\"").append(topologyId)
                .append("\",\"epoch\":").append(epoch)
                .append(",\"replication\":{\"factor\":").append(factor)
                .append("},\"strategy\":{\"kind\":\"rendezvous\",\"virtualNodesPerWeightUnit\":")
                .append(perWeightUnit).append(",\"maxVirtualNodesPerNode\":1024},\"nodes\":[");
        for (int index = 0; index < nodes; index++) {
            json.append(index == 0 ? "" : ",")
                    .append("{\"id\":\"n").append(index).append("\",\"weight\":1}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A ring document over two failure domain levels, which read affinity reorders against. */
    static byte[] ringWithDomains(String topologyId, long epoch, int nodes, int factor) {
        StringBuilder json = new StringBuilder();
        json.append("{\"formatVersion\":\"1.0\",\"topologyId\":\"").append(topologyId)
                .append("\",\"epoch\":").append(epoch)
                .append(",\"domainLevels\":[\"region\",\"zone\"]")
                .append(",\"replication\":{\"factor\":").append(factor)
                .append("},\"strategy\":{\"kind\":\"ring\",\"tokenAssignment\":\"derived\"")
                .append(",\"tokensPerWeightUnit\":64,\"maxTokensPerNode\":4096},\"nodes\":[");
        for (int index = 0; index < nodes; index++) {
            json.append(index == 0 ? "" : ",")
                    .append("{\"id\":\"n").append(index).append("\",\"weight\":1,\"domains\":{")
                    .append("\"region\":\"r").append(index % 2).append("\",\"zone\":\"z")
                    .append(index % 3).append("\"}}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }
}
