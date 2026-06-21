package com.javaee.docmanager.ai.aiops;

import com.javaee.docmanager.ai.util.TokenUsageUtils;
import com.javaee.docmanager.common.utils.RedisUtils;
import com.javaee.docmanager.security.UserContext;
import com.javaee.docmanager.user.entity.User;
import com.javaee.docmanager.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 监控服务 — Redis 热路径 + MySQL 持久化
 *
 * 写入：Redis Hash INCRBY（每次 AI 调用）
 * 读取：Redis 优先，回源 MySQL 兜底
 * 滑动窗口：Redis ZSet 记录请求时间戳，检测异常
 */
@Component
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final UserMapper userMapper;
    private final RedisUtils redisUtils;

    private static final String METRICS_PREFIX = "metrics:user:";
    private static final String GLOBAL_KEY = "metrics:global";
    private static final String WINDOW_PREFIX = "window:user:";
    private static final String ALERT_PREFIX = "alert:user:";

    private static final int WINDOW_HOURS = 24;
    private static final int ALERT_THRESHOLD_PER_HOUR = 100;

    // Redis field → MySQL 列名映射
    private static final Map<String, String> COLUMN_MAP = Map.of(
            "ragTokensInput", "rag_tokens_input",
            "ragTokensOutput", "rag_tokens_output",
            "pptTokensInput", "ppt_tokens_input",
            "pptTokensOutput", "ppt_tokens_output",
            "ragDocCount", "rag_doc_count",
            "ragSliceCount", "rag_slice_count",
            "pptCount", "ppt_count"
    );

    // 计数器名称 → Redis field / MySQL 列名映射
    private static final Map<String, String> FIELD_MAP = Map.of(
            "rag.tokens.input", "ragTokensInput",
            "rag.tokens.output", "ragTokensOutput",
            "ppt.tokens.input", "pptTokensInput",
            "ppt.tokens.output", "pptTokensOutput",
            "rag.docs", "ragDocCount",
            "rag.slices", "ragSliceCount",
            "ppt.generated", "pptCount",
            "rag.queries", "ragQueryCount",
            "ppt.chats", "pptChatCount"
    );

    public MonitoringService(UserMapper userMapper, RedisUtils redisUtils) {
        this.userMapper = userMapper;
        this.redisUtils = redisUtils;
    }

    /**
     * 计数器递增（核心热路径，每次 AI 调用）
     */
    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }

    public void incrementCounter(String name, long delta) {
        incrementCounter(UserContext.getCurrentUserId(), name, delta);
    }

    public void incrementCounter(Long userId, String name, long delta) {
        if (delta == 0) {
            return;
        }
        if (userId == null) {
            userId = 0L; // MCP等无用户上下文的请求，记到全局用户0
        }
        String field = FIELD_MAP.get(name);
        if (field == null) {
            log.warn("未知指标名称: {}", name);
            return;
        }
        // Redis 原子递增（首次写入前从 MySQL 初始化，保证累计值正确）
        String redisKey = METRICS_PREFIX + userId;
        if (userId > 0 && !Boolean.TRUE.equals(redisUtils.hHasKey(redisKey, field))) {
            long base = readFieldFromDb(userId, name);
            if (base > 0) {
                redisUtils.hSet(redisKey, field, base);
            }
        }
        redisUtils.hIncrement(redisKey, field, delta);
        redisUtils.expire(redisKey, 30, TimeUnit.DAYS);
        // 同步持久化到 MySQL
        if (userId > 0) {
            persistIncrement(userId, field, delta);
        }
        if (name.contains("tokens")) {
            log.info("Token 指标已记录: userId={}, name={}, delta={}", userId, name, delta);
        } else {
            log.debug("指标递增: userId={}, name={}, delta={}", userId, name, delta);
        }
        // 全局聚合
        redisUtils.hIncrement(GLOBAL_KEY, field, delta);
        // 滑动窗口记录（仅记录 token 类指标）
        if (name.contains("tokens")) {
            recordWindow(userId);
        }
    }

    /**
     * 记录一次 LLM 调用的 token 用量（rag / ppt）
     */
    public void recordTokenUsage(Long userId, String scope, int inputTokens, int outputTokens,
                               int promptChars, int responseChars) {
        int[] tokens = TokenUsageUtils.withCharFallback(inputTokens, outputTokens, promptChars, responseChars);
        if (tokens[0] <= 0 && tokens[1] <= 0) {
            log.warn("Token 用量为空，跳过记录: scope={}, promptChars={}, responseChars={}",
                    scope, promptChars, responseChars);
            return;
        }
        Long resolvedUserId = userId != null ? userId : UserContext.getCurrentUserId();
        incrementCounter(resolvedUserId, scope + ".tokens.input", tokens[0]);
        incrementCounter(resolvedUserId, scope + ".tokens.output", tokens[1]);
    }

    private void persistIncrement(Long userId, String field, long delta) {
        try {
            switch (field) {
                case "ragTokensInput" -> userMapper.incrementRagTokensInput(userId, delta);
                case "ragTokensOutput" -> userMapper.incrementRagTokensOutput(userId, delta);
                case "pptTokensInput" -> userMapper.incrementPptTokensInput(userId, delta);
                case "pptTokensOutput" -> userMapper.incrementPptTokensOutput(userId, delta);
                case "ragDocCount" -> userMapper.incrementRagDocCount(userId, delta);
                case "ragSliceCount" -> userMapper.incrementRagSliceCount(userId, delta);
                case "pptCount" -> userMapper.incrementPptCount(userId, delta);
                default -> {
                    String column = COLUMN_MAP.get(field);
                    if (column != null) {
                        userMapper.incrementField(userId, column, delta);
                    }
                }
            }
        } catch (Exception e) {
            log.error("指标写入 MySQL 失败: userId={}, field={}, delta={}, error={}",
                    userId, field, delta, e.getMessage());
        }
    }

    /**
     * 滑动窗口：记录每次请求的时间戳
     */
    private void recordWindow(Long userId) {
        String key = WINDOW_PREFIX + userId;
        double now = System.currentTimeMillis();
        redisUtils.zAdd(key, UUID.randomUUID().toString(), now);
        // 清理 24 小时前的记录
        long cutoff = System.currentTimeMillis() - (long) WINDOW_HOURS * 3600_000L;
        redisUtils.zRemoveRangeByScore(key, 0, cutoff);
        redisUtils.expire(key, WINDOW_HOURS + 1, TimeUnit.HOURS);
    }

    /**
     * 异常检测：1小时内请求数是否超过阈值
     * @return true 表示检测到异常
     */
    public boolean checkAnomaly(Long userId) {
        String key = WINDOW_PREFIX + userId;
        long cutoff = System.currentTimeMillis() - 3600_000L;
        Long count = redisUtils.zCount(key, cutoff, Double.MAX_VALUE);
        if (count != null && count > ALERT_THRESHOLD_PER_HOUR) {
            // 限流告警：每用户每小时最多告警一次
            String alertKey = ALERT_PREFIX + userId;
            Boolean set = redisUtils.setIfAbsent(alertKey, "1", 1, TimeUnit.HOURS);
            if (Boolean.TRUE.equals(set)) {
                log.warn("用户 {} 1小时内请求 {} 次，触发告警", userId, count);
            }
            return true;
        }
        return false;
    }

    /**
     * 获取用户最近1小时的请求数
     */
    public Long getRequestCountLastHour(Long userId) {
        String key = WINDOW_PREFIX + userId;
        long cutoff = System.currentTimeMillis() - 3600_000L;
        Long count = redisUtils.zCount(key, cutoff, Double.MAX_VALUE);
        return count != null ? count : 0L;
    }

    /**
     * 获取单个计数器
     */
    public long getCounter(String name) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return 0;
        return getCounterForUser(userId, name);
    }

    public long getCounterForUser(Long userId, String name) {
        String field = FIELD_MAP.get(name);
        if (field == null) return 0;
        User user = userMapper.selectById(userId);
        if (user == null) return 0;
        Map<Object, Object> raw = redisUtils.hGetAll(METRICS_PREFIX + userId);
        return maxMetric(readFieldFromUser(user, name), raw.get(field));
    }

    public long getAiChatCountForUser(Long userId) {
        if (userId == null) {
            return 0;
        }
        return getCounterForUser(userId, "rag.queries") + getCounterForUser(userId, "ppt.chats");
    }

    private long readFieldFromUser(User user, String name) {
        return switch (name) {
            case "rag.tokens.input" -> zeroIfNull(user.getRagTokensInput());
            case "rag.tokens.output" -> zeroIfNull(user.getRagTokensOutput());
            case "ppt.tokens.input" -> zeroIfNull(user.getPptTokensInput());
            case "ppt.tokens.output" -> zeroIfNull(user.getPptTokensOutput());
            case "rag.docs" -> zeroIfNull(user.getRagDocCount());
            case "rag.slices" -> zeroIfNull(user.getRagSliceCount());
            case "ppt.generated" -> zeroIfNull(user.getPptCount());
            default -> 0;
        };
    }

    /**
     * 获取所有指标（MySQL + Redis 取较大值，避免归档或异步导致不一致）
     */
    public Map<String, Object> getAllMetrics() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return Collections.emptyMap();

        User user = userMapper.selectById(userId);
        if (user == null) return Collections.emptyMap();

        Map<Object, Object> raw = redisUtils.hGetAll(METRICS_PREFIX + userId);

        long ragIn = maxMetric(zeroIfNull(user.getRagTokensInput()), raw.get("ragTokensInput"));
        long ragOut = maxMetric(zeroIfNull(user.getRagTokensOutput()), raw.get("ragTokensOutput"));
        long pptIn = maxMetric(zeroIfNull(user.getPptTokensInput()), raw.get("pptTokensInput"));
        long pptOut = maxMetric(zeroIfNull(user.getPptTokensOutput()), raw.get("pptTokensOutput"));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("ragTokensInput", ragIn);
        metrics.put("ragTokensOutput", ragOut);
        metrics.put("pptTokensInput", pptIn);
        metrics.put("pptTokensOutput", pptOut);
        metrics.put("ragDocCount", maxMetric(zeroIfNull(user.getRagDocCount()), raw.get("ragDocCount")));
        metrics.put("ragSliceCount", maxMetric(zeroIfNull(user.getRagSliceCount()), raw.get("ragSliceCount")));
        metrics.put("pptCount", maxMetric(zeroIfNull(user.getPptCount()), raw.get("pptCount")));
        metrics.put("totalTokensInput", ragIn + pptIn);
        metrics.put("totalTokensOutput", ragOut + pptOut);
        metrics.put("requestCountLastHour", getRequestCountLastHour(userId));
        return metrics;
    }

    private long maxMetric(long dbValue, Object redisValue) {
        return Math.max(dbValue, toLong(redisValue));
    }

    /**
     * 获取全局聚合指标
     */
    public Map<String, Object> getGlobalMetrics() {
        Map<Object, Object> raw = redisUtils.hGetAll(GLOBAL_KEY);
        if (!raw.isEmpty()) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("totalRagTokensInput", toLong(raw.getOrDefault("ragTokensInput", 0L)));
            metrics.put("totalRagTokensOutput", toLong(raw.getOrDefault("ragTokensOutput", 0L)));
            metrics.put("totalPptTokensInput", toLong(raw.getOrDefault("pptTokensInput", 0L)));
            metrics.put("totalPptTokensOutput", toLong(raw.getOrDefault("pptTokensOutput", 0L)));
            return metrics;
        }
        return Collections.emptyMap();
    }

    /**
     * 重置用户指标
     */
    public void resetMetrics() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return;
        // 清 Redis
        redisUtils.delete(METRICS_PREFIX + userId);
        redisUtils.delete(WINDOW_PREFIX + userId);
        redisUtils.delete(ALERT_PREFIX + userId);
        // 清 MySQL
        userMapper.resetCounters(userId);
        log.info("用户指标已重置: userId={}", userId);
    }

    /**
     * 从 MySQL 读取单个指标
     */
    private long readFieldFromDb(Long userId, String name) {
        User user = userMapper.selectById(userId);
        if (user == null) return 0;
        return readFieldFromUser(user, name);
    }

    private long loadFromDb(Long userId, String name) {
        return readFieldFromDb(userId, name);
    }

    /**
     * 构建今日实时指标快照（供趋势图展示当天数据）
     */
    public MetricsDaily buildTodaySnapshot(Long userId) {
        if (userId == null) return null;
        User user = userMapper.selectById(userId);
        if (user == null) return null;

        MetricsDaily daily = new MetricsDaily();
        daily.setUserId(userId);
        daily.setDate(LocalDate.now());
        daily.setRagTokensInput(zeroIfNull(user.getRagTokensInput()));
        daily.setRagTokensOutput(zeroIfNull(user.getRagTokensOutput()));
        daily.setPptTokensInput(zeroIfNull(user.getPptTokensInput()));
        daily.setPptTokensOutput(zeroIfNull(user.getPptTokensOutput()));
        daily.setRagDocCount((int) zeroIfNull(user.getRagDocCount()));
        daily.setRagSliceCount((int) zeroIfNull(user.getRagSliceCount()));
        daily.setPptCount((int) zeroIfNull(user.getPptCount()));
        return daily;
    }

    /**
     * 获取所有有指标数据的用户 key（供刷盘任务使用）
     */
    public Set<String> getAllUserMetricKeys() {
        return redisUtils.keys(METRICS_PREFIX + "*");
    }

    /**
     * 从 Redis key 提取 userId
     */
    public Long extractUserId(String key) {
        try {
            return Long.parseLong(key.substring(METRICS_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取用户的 Redis 指标原始数据
     */
    public Map<Object, Object> getUserMetricsRaw(Long userId) {
        return redisUtils.hGetAll(METRICS_PREFIX + userId);
    }

    /**
     * 获取所有用户的滑动窗口 key（供每日归档使用）
     */
    public Set<String> getAllWindowKeys() {
        return redisUtils.keys(WINDOW_PREFIX + "*");
    }

    private long toLong(Object val) {
        if (val instanceof Number) return ((Number) val).longValue();
        if (val instanceof String) {
            try { return Long.parseLong((String) val); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private long zeroIfNull(Long val) {
        return val != null ? val : 0;
    }
}
