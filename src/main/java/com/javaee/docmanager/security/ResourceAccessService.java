package com.javaee.docmanager.security;

import com.javaee.docmanager.doc.entity.DocumentFile;
import com.javaee.docmanager.user.entity.User;
import com.javaee.docmanager.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 资源归属鉴权：普通用户仅能访问自己的资源，管理员可访问全部。
 */
@Service
@RequiredArgsConstructor
public class ResourceAccessService {

    private final UserMapper userMapper;

    public boolean isAdmin() {
        return UserContext.isAdmin();
    }

    public boolean canAccessByOwner(String ownerUsername, Long ownerUserId) {
        if (isAdmin()) {
            return true;
        }
        String currentUsername = UserContext.getCurrentUsername();
        if (ownerUsername != null && ownerUsername.equals(currentUsername)) {
            return true;
        }
        Long currentUserId = UserContext.getCurrentUserId();
        return ownerUserId != null && ownerUserId.equals(currentUserId);
    }

    public void assertCanAccess(String ownerUsername, Long ownerUserId) {
        if (!canAccessByOwner(ownerUsername, ownerUserId)) {
            throw new RuntimeException("无权访问该资源");
        }
    }

    public void assertCanAccess(DocumentFile doc) {
        if (doc == null) {
            throw new RuntimeException("文档不存在");
        }
        assertCanAccess(doc.getCreateBy(), doc.getUserId());
    }

    public void assertCanAccessRagMetadata(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return;
        }
        if (isAdmin()) {
            return;
        }
        String indexedBy = stringVal(meta.get("indexedBy"));
        if (indexedBy == null || !indexedBy.equals(UserContext.getCurrentUsername())) {
            throw new RuntimeException("无权访问该知识库文档");
        }
    }

    /**
     * 管理员可按用户 ID 筛选；普通用户只能查自己的数据，忽略筛选参数。
     */
    public Long resolveListOwnerFilter(Long requestedOwnerUserId) {
        if (isAdmin()) {
            return requestedOwnerUserId;
        }
        return UserContext.getCurrentUserId();
    }

    public String resolveOwnerUsername(Long ownerUserId) {
        if (ownerUserId == null) {
            return null;
        }
        User user = userMapper.selectById(ownerUserId);
        return user != null ? user.getUsername() : null;
    }

    public void assertAdmin() {
        if (!isAdmin()) {
            throw new RuntimeException("仅管理员可执行此操作");
        }
    }

    private static String stringVal(Object v) {
        return v == null ? null : v.toString();
    }
}
