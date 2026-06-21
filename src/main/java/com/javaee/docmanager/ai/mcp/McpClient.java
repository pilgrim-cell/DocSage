package com.javaee.docmanager.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP（Model Context Protocol）客户端：从外部 MCP Server 拉取工具列表并执行 tool call。
 * 未配置 mcp.server.url 时返回空工具列表，ChatService 自动退化为普通对话。
 */
@Component
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger RPC_ID = new AtomicInteger(1);

    @Value("${mcp.server.url:}")
    private String serverUrl;

    @Value("${mcp.server.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate = new RestTemplate();
    private volatile List<Map<String, Object>> cachedTools = List.of();

    @PostConstruct
    public void init() {
        if (isConfigured()) {
            try {
                refreshTools();
                log.info("MCP 客户端已连接: url={}, tools={}", serverUrl, cachedTools.size());
            } catch (Exception e) {
                log.warn("MCP 工具列表加载失败，将以空工具集运行: {}", e.getMessage());
                cachedTools = List.of();
            }
        } else {
            log.info("MCP 未启用（mcp.server.url 未配置）");
        }
    }

    public boolean isConfigured() {
        return enabled && serverUrl != null && !serverUrl.isBlank();
    }

    /**
     * 返回 Anthropic tools 格式的工具定义列表。
     */
    public List<Map<String, Object>> getAllTools() {
        if (!isConfigured()) {
            return List.of();
        }
        if (cachedTools.isEmpty()) {
            refreshTools();
        }
        return cachedTools;
    }

    /**
     * 执行 MCP 工具调用。
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (!isConfigured()) {
            return "MCP 未配置";
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Map.of());

            JsonNode result = jsonRpc("tools/call", params);
            if (result == null) {
                return "工具调用无响应";
            }

            JsonNode content = result.path("content");
            if (content.isArray() && content.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                return sb.length() > 0 ? sb.toString() : result.toString();
            }
            return result.toString();
        } catch (Exception e) {
            log.error("MCP 工具调用失败: tool={}", toolName, e);
            return "工具调用失败: " + e.getMessage();
        }
    }

    public void refreshTools() {
        if (!isConfigured()) {
            cachedTools = List.of();
            return;
        }
        try {
            JsonNode result = jsonRpc("tools/list", Map.of());
            List<Map<String, Object>> tools = new ArrayList<>();
            JsonNode toolList = result != null ? result.path("tools") : null;
            if (toolList != null && toolList.isArray()) {
                for (JsonNode tool : toolList) {
                    tools.add(toAnthropicTool(tool));
                }
            }
            cachedTools = List.copyOf(tools);
        } catch (Exception e) {
            log.warn("刷新 MCP 工具列表失败: {}", e.getMessage());
        }
    }

    private Map<String, Object> toAnthropicTool(JsonNode tool) {
        Map<String, Object> anthropicTool = new LinkedHashMap<>();
        anthropicTool.put("name", tool.path("name").asText());
        anthropicTool.put("description", tool.path("description").asText(""));

        JsonNode inputSchema = tool.path("inputSchema");
        if (inputSchema.isMissingNode() || inputSchema.isNull()) {
            inputSchema = tool.path("input_schema");
        }
        if (!inputSchema.isMissingNode() && !inputSchema.isNull()) {
            anthropicTool.put("input_schema", MAPPER.convertValue(inputSchema, Map.class));
        } else {
            Map<String, Object> defaultSchema = new LinkedHashMap<>();
            defaultSchema.put("type", "object");
            defaultSchema.put("properties", Map.of());
            anthropicTool.put("input_schema", defaultSchema);
        }
        return anthropicTool;
    }

    private JsonNode jsonRpc(String method, Map<String, Object> params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", RPC_ID.getAndIncrement());
        body.put("method", method);
        body.put("params", params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        String json = MAPPER.writeValueAsString(body);
        HttpEntity<String> entity = new HttpEntity<>(json, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                serverUrl,
                HttpMethod.POST,
                entity,
                String.class);

        JsonNode root = MAPPER.readTree(response.getBody());
        if (root.has("error")) {
            throw new RuntimeException(root.path("error").path("message").asText("MCP RPC 错误"));
        }
        return root.path("result");
    }
}
