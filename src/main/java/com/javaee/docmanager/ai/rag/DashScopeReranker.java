package com.javaee.docmanager.ai.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 文本重排序（gte-rerank-v2 / qwen3-rerank）。
 */
@Component
public class DashScopeReranker {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RERANK_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${spring.ai.dashscope.rerank.model:gte-rerank-v2}")
    private String model;

    /**
     * 对文档列表重排序，返回与输入 documents 等长的分数列表（按原始顺序）。
     */
    public List<Float> rerank(String query, List<String> documents, int topK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DashScope API Key 未配置，跳过重排序");
            return fallbackScores(documents.size());
        }

        try {
            return callRerankApi(query, documents, topK);
        } catch (Exception e) {
            log.error("DashScope Rerank 调用失败，使用降级分数: {}", e.getMessage());
            return fallbackScores(documents.size());
        }
    }

    private List<Float> callRerankApi(String query, List<String> documents, int topK) throws Exception {
        RestTemplate restTemplate = new RestTemplate();

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("top_n", Math.min(topK, documents.size()));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String json = MAPPER.writeValueAsString(body);
        HttpEntity<String> entity = new HttpEntity<>(json, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                RERANK_URL,
                HttpMethod.POST,
                entity,
                String.class);

        JsonNode root = MAPPER.readTree(response.getBody());
        JsonNode results = root.path("output").path("results");

        float[] scores = new float[documents.size()];
        if (results.isArray()) {
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                float score = (float) item.path("relevance_score").asDouble(0);
                if (index >= 0 && index < scores.length) {
                    scores[index] = score;
                }
            }
        }

        List<Float> list = new ArrayList<>(documents.size());
        for (float s : scores) {
            list.add(s);
        }
        log.info("DashScope Rerank 完成: docs={}, topK={}", documents.size(), topK);
        return list;
    }

    private List<Float> fallbackScores(int size) {
        List<Float> scores = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            scores.add(1.0f - i * 0.01f);
        }
        return scores;
    }
}
