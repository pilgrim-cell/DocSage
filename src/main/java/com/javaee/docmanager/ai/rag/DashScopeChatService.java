package com.javaee.docmanager.ai.rag;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.javaee.docmanager.ai.aiops.MonitoringService;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * DashScope 流式对话服务（用于 RAG SSE 问答）。
 */
@Service
public class DashScopeChatService {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatService.class);

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    @Value("${spring.ai.dashscope.chat.model:qwen-plus}")
    private String model;

    @Autowired
    private MonitoringService monitoringService;

    /**
     * 流式生成，每收到一个 token 片段调用 onToken，完成后调用 onComplete。
     */
    public void chatStream(Long userId, String prompt, Consumer<String> onToken, Runnable onComplete) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DashScope API Key 未配置（spring.ai.dashscope.api-key）");
        }

        try {
            Generation generation = new Generation();
            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .messages(java.util.List.of(userMsg))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .incrementalOutput(true)
                    .build();

            Flowable<GenerationResult> flowable = generation.streamCall(param);
            StringBuilder full = new StringBuilder();
            final int[] inputTokens = {0};
            final int[] outputTokens = {0};

            flowable.blockingForEach(chunk -> {
                if (chunk.getUsage() != null) {
                    if (chunk.getUsage().getInputTokens() != null) {
                        inputTokens[0] = chunk.getUsage().getInputTokens();
                    }
                    if (chunk.getUsage().getOutputTokens() != null) {
                        outputTokens[0] = chunk.getUsage().getOutputTokens();
                    }
                }
                if (chunk.getOutput() != null
                        && chunk.getOutput().getChoices() != null
                        && !chunk.getOutput().getChoices().isEmpty()) {
                    String delta = chunk.getOutput().getChoices().get(0).getMessage().getContent();
                    if (delta != null && !delta.isEmpty()) {
                        full.append(delta);
                        onToken.accept(delta);
                    }
                }
            });

            monitoringService.recordTokenUsage(userId, "rag", inputTokens[0], outputTokens[0],
                    prompt.length(), full.length());

            log.info("DashScope 流式生成完成: length={}", full.length());
            if (onComplete != null) {
                onComplete.run();
            }
        } catch (Exception e) {
            log.error("DashScope 流式对话失败", e);
            throw new RuntimeException("流式生成失败: " + e.getMessage(), e);
        }
    }
}
