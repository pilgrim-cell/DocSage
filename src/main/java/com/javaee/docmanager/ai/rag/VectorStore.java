package com.javaee.docmanager.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * 向量存储
 * 使用 Qdrant REST API 实现向量存储和 HNSW 索引检索
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private static final String COLLECTION = "documents_v2";
    private static final int VECTOR_SIZE = 1024;

    @Autowired
    private RestClient qdrantRestClient;

    @Autowired
    private ObjectMapper objectMapper;

    private boolean collectionReady = false;

    /**
     * 确保集合存在
     */
    private void ensureCollection() {
        if (collectionReady) return;
        try {
            // 创建集合（幂等，已存在则忽略）
            String body = """
                {
                    "vectors": {
                        "size": %d,
                        "distance": "Cosine"
                    }
                }
                """.formatted(VECTOR_SIZE);

            String response = qdrantRestClient.put()
                    .uri("/collections/" + COLLECTION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            log.info("Qdrant 集合 '{}' 就绪", COLLECTION);
            ensurePayloadIndexes();
            collectionReady = true;
        } catch (Exception e) {
            log.warn("Qdrant 集合创建失败（可能已存在）: {}", e.getMessage());
            ensurePayloadIndexes();
            collectionReady = true;
        }
    }

    /**
     * 为 payload 字段建立索引，否则按 documentId 过滤检索会返回空结果。
     */
    private void ensurePayloadIndexes() {
        for (String field : List.of("documentId", "chunkId", "sourceDocumentId", "fileName")) {
            try {
                String body = """
                    {
                        "field_name": "%s",
                        "field_schema": "keyword"
                    }
                    """.formatted(field);
                qdrantRestClient.put()
                        .uri("/collections/" + COLLECTION + "/index")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Qdrant payload 索引已就绪: {}", field);
            } catch (Exception e) {
                log.debug("Qdrant payload 索引创建跳过: field={}, reason={}", field, e.getMessage());
            }
        }
    }

    /**
     * 从 chunkId 生成确定性 UUID（相同输入总是相同输出）
     */
    private UUID toPointId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes());
    }

    /**
     * 存储向量
     */
    public void store(String id, float[] vector, Map<String, Object> metadata) {
        ensureCollection();
        log.info("存储向量到 Qdrant: chunkId={}, dimension={}", id, vector.length);

        try {
            UUID pointId = toPointId(id);
            String documentId = extractDocumentId(id);

            // 构建 payload JSON（包含 chunkId / documentId 用于搜索与按文档清理）
            StringBuilder payloadJson = new StringBuilder("{");
            payloadJson.append("\"chunkId\":\"").append(escapeJson(id)).append("\"");
            payloadJson.append(",\"documentId\":\"").append(escapeJson(documentId)).append("\"");
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                payloadJson.append(",");
                payloadJson.append("\"").append(entry.getKey()).append("\":");
                Object val = entry.getValue();
                if (val instanceof String) {
                    payloadJson.append("\"").append(escapeJson((String) val)).append("\"");
                } else if (val instanceof Number || val instanceof Boolean) {
                    payloadJson.append(val);
                } else {
                    payloadJson.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
                }
            }
            payloadJson.append("}");

            // 构建向量 JSON
            StringBuilder vectorJson = new StringBuilder("[");
            for (int i = 0; i < vector.length; i++) {
                if (i > 0) vectorJson.append(",");
                vectorJson.append(vector[i]);
            }
            vectorJson.append("]");

            String body = """
                {
                    "points": [
                        {
                            "id": "%s",
                            "vector": %s,
                            "payload": %s
                        }
                    ]
                }
                """.formatted(pointId, vectorJson, payloadJson);

            qdrantRestClient.put()
                    .uri("/collections/" + COLLECTION + "/points")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("向量存储成功: chunkId={}, pointId={}", id, pointId);
        } catch (Exception e) {
            log.error("向量存储失败", e);
            throw new RuntimeException("向量存储失败: " + e.getMessage(), e);
        }
    }

    /**
     * 在指定文档范围内搜索相似向量
     */
    public List<Map<String, Object>> searchByDocumentId(float[] queryVector, String documentId, int topK) {
        ensureCollection();
        if (documentId == null || documentId.isBlank()) {
            return List.of();
        }
        log.info("Qdrant 按文档检索: documentId={}, topK={}", documentId, topK);

        try {
            StringBuilder vectorJson = new StringBuilder("[");
            for (int i = 0; i < queryVector.length; i++) {
                if (i > 0) vectorJson.append(",");
                vectorJson.append(queryVector[i]);
            }
            vectorJson.append("]");

            String body = """
                {
                    "vector": %s,
                    "limit": %d,
                    "with_payload": true,
                    "filter": {
                        "must": [
                            {
                                "key": "documentId",
                                "match": { "value": "%s" }
                            }
                        ]
                    }
                }
                """.formatted(vectorJson, topK, escapeJson(documentId));

            String response = qdrantRestClient.post()
                    .uri("/collections/" + COLLECTION + "/points/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseSearchResults(response);
        } catch (Exception e) {
            log.warn("Qdrant 按文档检索失败: documentId={}, error={}", documentId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 搜索相似向量（HNSW 索引，O(log n)）
     */
    public List<Map<String, Object>> search(float[] queryVector, int topK) {
        ensureCollection();
        log.info("Qdrant 搜索相似向量: topK={}", topK);

        try {
            StringBuilder vectorJson = new StringBuilder("[");
            for (int i = 0; i < queryVector.length; i++) {
                if (i > 0) vectorJson.append(",");
                vectorJson.append(queryVector[i]);
            }
            vectorJson.append("]");

            String body = """
                {
                    "vector": %s,
                    "limit": %d,
                    "with_payload": true
                }
                """.formatted(vectorJson, topK);

            String response = qdrantRestClient.post()
                    .uri("/collections/" + COLLECTION + "/points/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return parseSearchResults(response);
        } catch (Exception e) {
            log.error("向量搜索失败", e);
            throw new RuntimeException("向量搜索失败: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> parseSearchResults(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode resultNode = root.get("result");

        List<Map<String, Object>> results = new ArrayList<>();
        if (resultNode != null && resultNode.isArray()) {
            for (JsonNode point : resultNode) {
                Map<String, Object> item = new HashMap<>();
                item.put("similarity", point.get("score").floatValue());

                JsonNode payload = point.get("payload");
                if (payload != null) {
                    JsonNode chunkIdNode = payload.get("chunkId");
                    item.put("id", chunkIdNode != null ? chunkIdNode.asText() : point.get("id").asText());
                    item.put("chunkId", chunkIdNode != null ? chunkIdNode.asText() : item.get("id"));
                    JsonNode docIdNode = payload.get("documentId");
                    if (docIdNode != null && !docIdNode.asText().isBlank()) {
                        item.put("documentId", docIdNode.asText());
                    }

                    Iterator<String> fields = payload.fieldNames();
                    while (fields.hasNext()) {
                        String field = fields.next();
                        if ("chunkId".equals(field) || "documentId".equals(field)) continue;
                        JsonNode val = payload.get(field);
                        if (val.isTextual()) {
                            item.put(field, val.asText());
                        } else if (val.isNumber()) {
                            item.put(field, val.asDouble());
                        } else if (val.isBoolean()) {
                            item.put(field, val.asBoolean());
                        } else {
                            item.put(field, val.asText());
                        }
                    }
                } else {
                    item.put("id", point.get("id").asText());
                }

                results.add(item);
            }
        }

        log.info("搜索完成，找到 {} 个结果", results.size());
        return results;
    }

    /**
     * 按文档 ID 删除该文档全部向量（含孤儿点）
     */
    public void deleteByDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return;
        }
        deleteByPayloadMatch("documentId", documentId);
    }

    /**
     * 按 payload 字段精确匹配删除向量
     */
    public void deleteByPayloadMatch(String field, String value) {
        ensureCollection();
        if (field == null || field.isBlank() || value == null || value.isBlank()) {
            return;
        }
        try {
            String body = """
                {
                    "filter": {
                        "must": [
                            {
                                "key": "%s",
                                "match": { "value": "%s" }
                            }
                        ]
                    }
                }
                """.formatted(escapeJson(field), escapeJson(value));

            qdrantRestClient.post()
                    .uri("/collections/" + COLLECTION + "/points/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Qdrant 按 {}={} 删除向量完成", field, value);
        } catch (Exception e) {
            log.warn("Qdrant 按 payload 删除失败: {}={}, error={}", field, value, e.getMessage());
        }
    }

    /**
     * 删除向量
     */
    public void delete(String chunkId) {
        ensureCollection();
        UUID pointId = toPointId(chunkId);
        log.info("从 Qdrant 删除向量: chunkId={}, pointId={}", chunkId, pointId);

        try {
            String body = """
                {
                    "points": ["%s"]
                }
                """.formatted(pointId);

            qdrantRestClient.post()
                    .uri("/collections/" + COLLECTION + "/points/delete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("向量删除成功: chunkId={}, pointId={}", chunkId, pointId);
        } catch (Exception e) {
            log.error("向量删除失败", e);
            throw new RuntimeException("向量删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除整个集合（用于清空所有向量数据）
     */
    public void deleteCollection() {
        try {
            qdrantRestClient.delete()
                    .uri("/collections/" + COLLECTION)
                    .retrieve()
                    .toBodilessEntity();
            collectionReady = false;
            log.info("Qdrant 集合 '{}' 已删除", COLLECTION);
        } catch (Exception e) {
            log.warn("删除 Qdrant 集合失败（可能不存在）: {}", e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String extractDocumentId(String chunkId) {
        int idx = chunkId.lastIndexOf("::");
        return idx > 0 ? chunkId.substring(0, idx) : chunkId;
    }
}
