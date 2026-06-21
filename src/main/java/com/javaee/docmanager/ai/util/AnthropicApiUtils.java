package com.javaee.docmanager.ai.util;

/**
 * Anthropic 兼容 API 工具（支持 base-url 已含 /v1 的代理网关）
 */
public final class AnthropicApiUtils {

    private AnthropicApiUtils() {
    }

    public static String buildMessagesUrl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            return base + "/messages";
        }
        return base + "/v1/messages";
    }
}
