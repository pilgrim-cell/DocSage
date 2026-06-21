package com.javaee.docmanager.ai.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话管理器
 * 管理对话生命周期和消息历史
 * 支持多轮对话和上下文管理
 */
@Component
public class ConversationManager {

    private static final Logger log = LoggerFactory.getLogger(ConversationManager.class);
    private static final String CONVERSATION_PREFIX = "conv:";
    private static final String USER_CONV_PREFIX = "user:convs:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Value("${ai.conversation.max-messages:100}")
    private int maxMessages;

    @Value("${ai.conversation.expiry-hours:24}")
    private int expiryHours;

    /**
     * 创建新对话
     * @param userId 用户ID
     * @return 对话ID
     */
    public String createConversation(String userId) {
        String normalizedUserId = String.valueOf(userId);
        String conversationId = UUID.randomUUID().toString();
        String key = CONVERSATION_PREFIX + conversationId;

        Map<String, Object> conversation = new HashMap<>();
        conversation.put("userId", normalizedUserId);
        conversation.put("createdAt", System.currentTimeMillis());
        conversation.put("updatedAt", System.currentTimeMillis());
        conversation.put("messages", new ArrayList<String>());

        redisTemplate.opsForHash().putAll(key, conversation);
        redisTemplate.expire(key, java.time.Duration.ofHours(expiryHours));
        redisTemplate.opsForSet().add(USER_CONV_PREFIX + normalizedUserId, conversationId);
        redisTemplate.expire(USER_CONV_PREFIX + normalizedUserId, java.time.Duration.ofHours(expiryHours));

        log.info("创建新对话: userId={}, conversationId={}", normalizedUserId, conversationId);
        return conversationId;
    }

    /**
     * 添加消息到对话
     * @param conversationId 对话ID
     * @param userMessage 用户消息
     * @param assistantMessage 助手消息
     */
    public void addMessage(String conversationId, String userMessage, String assistantMessage) {
        String key = CONVERSATION_PREFIX + conversationId;
        
        @SuppressWarnings("unchecked")
        List<String> messages = (List<String>) redisTemplate.opsForHash().get(key, "messages");
        if (messages == null) {
            messages = new ArrayList<>();
        }

        messages.add("User: " + userMessage);
        messages.add("Assistant: " + assistantMessage);

        if (messages.size() > maxMessages) {
            messages = messages.subList(messages.size() - maxMessages, messages.size());
        }

        redisTemplate.opsForHash().put(key, "messages", messages);
        redisTemplate.opsForHash().put(key, "updatedAt", System.currentTimeMillis());
        redisTemplate.expire(key, java.time.Duration.ofHours(expiryHours));

        log.debug("添加消息到对话: conversationId={}, messageCount={}", conversationId, messages.size());
    }

    /**
     * 获取对话历史
     * @param conversationId 对话ID
     * @return 消息列表
     */
    public List<String> getConversationHistory(String conversationId) {
        String key = CONVERSATION_PREFIX + conversationId;
        
        @SuppressWarnings("unchecked")
        List<String> messages = (List<String>) redisTemplate.opsForHash().get(key, "messages");
        return messages != null ? messages : Collections.emptyList();
    }

    /**
     * 删除对话
     * @param conversationId 对话ID
     */
    public void deleteConversation(String conversationId) {
        String key = CONVERSATION_PREFIX + conversationId;
        Object userId = redisTemplate.opsForHash().get(key, "userId");
        redisTemplate.delete(key);
        if (userId != null) {
            redisTemplate.opsForSet().remove(USER_CONV_PREFIX + userId, conversationId);
        }
        log.info("删除对话: conversationId={}", conversationId);
    }

    /**
     * 统计用户当前活跃 Agent 会话数
     */
    public int countUserConversations(String userId) {
        return getUserConversations(userId).size();
    }

    /**
     * 获取用户的所有对话
     * @param userId 用户ID
     * @return 对话ID列表
     */
    public List<String> getUserConversations(String userId) {
        String normalizedUserId = String.valueOf(userId);
        try {
            Set<Object> members = redisTemplate.opsForSet().members(USER_CONV_PREFIX + normalizedUserId);
            if (members != null && !members.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (Object member : members) {
                    if (member != null) {
                        ids.add(member.toString());
                    }
                }
                return ids;
            }

            // 兼容旧数据：从 conv:* 扫描
            Set<String> keys = redisTemplate.keys(CONVERSATION_PREFIX + "*");
            if (keys == null) {
                return Collections.emptyList();
            }

            List<String> conversationIds = new ArrayList<>();
            for (String key : keys) {
                Object convUserId = redisTemplate.opsForHash().get(key, "userId");
                if (userIdMatches(normalizedUserId, convUserId)) {
                    conversationIds.add(key.substring(CONVERSATION_PREFIX.length()));
                }
            }
            return conversationIds;
        } catch (Exception e) {
            log.warn("获取用户对话列表失败", e);
            return Collections.emptyList();
        }
    }

    private boolean userIdMatches(String expected, Object actual) {
        return expected != null && actual != null && expected.equals(String.valueOf(actual));
    }

    /**
     * 获取对话信息
     * @param conversationId 对话ID
     * @return 对话信息
     */
    public Map<String, Object> getConversationInfo(String conversationId) {
        String key = CONVERSATION_PREFIX + conversationId;
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        
        Map<String, Object> info = new HashMap<>();
        for (Map.Entry<Object, Object> entry : hash.entrySet()) {
            info.put(entry.getKey().toString(), entry.getValue());
        }
        return info;
    }
}
