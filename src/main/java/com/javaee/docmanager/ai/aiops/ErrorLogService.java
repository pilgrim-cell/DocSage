package com.javaee.docmanager.ai.aiops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 错误日志记录与 AI 简要分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ErrorLogService {

    private final ErrorLogMapper errorLogMapper;

    @Value("${aiops.error-log.ai-analysis.enabled:true}")
    private boolean aiAnalysisEnabled;

    @Async
    public void recordError(String source, Throwable throwable) {
        recordErrorSync(source, throwable);
    }

    public void recordErrorSync(String source, Throwable throwable) {
        if (throwable == null) {
            return;
        }
        try {
            String message = truncate(throwable.getMessage(), 2000);
            if (message == null || message.isBlank()) {
                message = throwable.getClass().getSimpleName();
            }

            ErrorLog existing = errorLogMapper.selectBySourceAndMessage(source, message);
            if (existing != null) {
                errorLogMapper.incrementCount(existing.getId(), existing.getAiAnalysis());
                return;
            }

            ErrorLog record = new ErrorLog();
            record.setSource(truncate(source, 255));
            record.setErrorMessage(message);
            record.setErrorCount(1);
            record.setAiAnalysis(buildQuickAnalysis(throwable));
            errorLogMapper.insert(record);
        } catch (Exception e) {
            log.warn("写入 error_log 失败: {}", e.getMessage());
        }
    }

    private String buildQuickAnalysis(Throwable throwable) {
        if (!aiAnalysisEnabled) {
            return null;
        }
        String type = throwable.getClass().getSimpleName();
        String hint = switch (type) {
            case "IllegalStateException", "IllegalArgumentException" -> "请检查请求参数或配置项是否完整。";
            case "RuntimeException" -> "业务处理异常，请查看后台日志定位具体模块。";
            default -> "系统异常，建议检查依赖服务（MySQL/Redis/MinIO/Qdrant/ES）是否可用。";
        };
        return "【自动分析】" + type + "：" + hint;
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    public static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
