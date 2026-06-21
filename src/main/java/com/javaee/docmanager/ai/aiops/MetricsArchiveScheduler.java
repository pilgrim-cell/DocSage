package com.javaee.docmanager.ai.aiops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * 每日指标归档：将 Redis 热指标快照写入 metrics_daily 表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsArchiveScheduler {

    private final MonitoringService monitoringService;
    private final MetricsDailyMapper metricsDailyMapper;

    /**
     * 每天 00:05 归档昨日指标；启动后 2 分钟执行一次补档（便于开发验证）。
     */
    @Scheduled(cron = "${aiops.metrics.archive.cron:0 5 0 * * ?}")
    public void archiveDailyMetrics() {
        archiveForDate(LocalDate.now().minusDays(1));
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = Long.MAX_VALUE)
    public void archiveOnStartup() {
        // 仅开发环境友好：启动后补一次当天快照
        archiveForDate(LocalDate.now());
    }

    public void archiveForDate(LocalDate date) {
        Set<String> keys = monitoringService.getAllUserMetricKeys();
        if (keys == null || keys.isEmpty()) {
            log.debug("无用户指标需要归档: date={}", date);
            return;
        }

        int count = 0;
        for (String key : keys) {
            Long userId = monitoringService.extractUserId(key);
            if (userId == null || userId <= 0) {
                continue;
            }
            try {
                Map<Object, Object> raw = monitoringService.getUserMetricsRaw(userId);
                if (raw == null || raw.isEmpty()) {
                    continue;
                }

                MetricsDaily daily = new MetricsDaily();
                daily.setUserId(userId);
                daily.setDate(date);
                daily.setRagTokensInput(toLong(raw.get("ragTokensInput")));
                daily.setRagTokensOutput(toLong(raw.get("ragTokensOutput")));
                daily.setPptTokensInput(toLong(raw.get("pptTokensInput")));
                daily.setPptTokensOutput(toLong(raw.get("pptTokensOutput")));
                daily.setRagDocCount(toInt(raw.get("ragDocCount")));
                daily.setRagSliceCount(toInt(raw.get("ragSliceCount")));
                daily.setPptCount(toInt(raw.get("pptCount")));

                metricsDailyMapper.insertOrUpdate(daily);

                count++;
            } catch (Exception e) {
                log.warn("归档用户指标失败: userId={}, error={}", userId, e.getMessage());
            }
        }
        log.info("指标归档完成: date={}, users={}", date, count);
    }

    private long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private int toInt(Object val) {
        return (int) toLong(val);
    }
}
