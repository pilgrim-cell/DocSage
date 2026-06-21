package com.javaee.docmanager.ai.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从 LLM 响应解析 token 用量；API 未返回时按字符数估算。
 */
public final class TokenUsageUtils {

    private TokenUsageUtils() {
    }

    public static int[] parseUsage(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return new int[]{0, 0};
        }
        JsonNode usage = root.path("usage");
        int input = firstPositive(usage, "input_tokens", "prompt_tokens");
        int output = firstPositive(usage, "output_tokens", "completion_tokens");
        if (input > 0 || output > 0) {
            return new int[]{input, output};
        }
        int total = usage.path("total_tokens").asInt(0);
        if (total > 0) {
            int half = total / 2;
            return new int[]{half, total - half};
        }
        return new int[]{0, 0};
    }

    /** @deprecated use {@link #parseUsage(JsonNode)} */
    public static int[] parseAnthropicUsage(JsonNode root) {
        return parseUsage(root);
    }

    public static int[] withCharFallback(int input, int output, int promptChars, int responseChars) {
        if (input <= 0 && promptChars > 0) {
            input = Math.max(1, promptChars / 3);
        }
        if (output <= 0 && responseChars > 0) {
            output = Math.max(1, responseChars / 3);
        }
        if (input <= 0 && output > 0) {
            input = Math.max(1, output / 2);
        }
        if (output <= 0 && input > 0) {
            output = Math.max(1, input / 2);
        }
        return new int[]{input, output};
    }

    private static int firstPositive(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return 0;
        }
        for (String field : fields) {
            int val = node.path(field).asInt(0);
            if (val > 0) {
                return val;
            }
        }
        return 0;
    }
}
