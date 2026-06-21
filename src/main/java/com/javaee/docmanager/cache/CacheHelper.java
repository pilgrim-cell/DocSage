package com.javaee.docmanager.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Objects;

/**
 * 缓存失效助手：写操作后统一清理 Redis 键及 Spring Cache 本地缓存。
 */
@Slf4j
@Component
public class CacheHelper {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private CacheManager cacheManager;

    /**
     * 数据更新后删除相关缓存键。
     */
    public void deleteAfterUpdate(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            try {
                Boolean deleted = redisTemplate.delete(key);
                log.debug("缓存失效: key={}, deleted={}", key, deleted);
                evictSpringCache(key);
            } catch (Exception e) {
                log.warn("缓存失效失败: key={}, error={}", key, e.getMessage());
            }
        }
    }

    private void evictSpringCache(String key) {
        if (cacheManager == null) {
            return;
        }
        cacheManager.getCacheNames().forEach(cacheName -> {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(key);
            }
        });
    }

    /**
     * 按模式批量删除（慎用，仅用于列表类缓存）。
     */
    public void deleteByPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        var keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.debug("按模式删除缓存: pattern={}, count={}", pattern, keys.size());
        }
    }

    public void deleteAll(String... keys) {
        deleteAfterUpdate(keys);
    }
}
