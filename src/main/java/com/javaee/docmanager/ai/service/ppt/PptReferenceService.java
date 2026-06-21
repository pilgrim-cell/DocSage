package com.javaee.docmanager.ai.service.ppt;

import com.javaee.docmanager.ai.rag.KnowledgeBase;
import com.javaee.docmanager.ai.rag.RagDocumentIndexer;
import com.javaee.docmanager.ai.rag.Reranker;
import com.javaee.docmanager.ai.rag.RagImportService;
import com.javaee.docmanager.ai.rag.TextExtractor;
import com.javaee.docmanager.doc.entity.DocumentFile;
import com.javaee.docmanager.doc.mapper.DocumentBranchMapper;
import com.javaee.docmanager.file.service.FileService;
import com.javaee.docmanager.file.util.FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PptReferenceService {

    private final TextExtractor textExtractor;
    private final RagDocumentIndexer ragDocumentIndexer;
    private final KnowledgeBase knowledgeBase;
    private final FileService fileService;
    private final DocumentBranchMapper documentBranchMapper;
    private final RagImportService ragImportService;

    @Value("${ppt.reference.primary-max-chars:12000}")
    private int primaryMaxChars;

    @Value("${ppt.reference.secondary-top-k:6}")
    private int secondaryTopK;

    private final Map<String, List<PptReferenceDoc>> sessionRefs = new ConcurrentHashMap<>();

    public String ensureConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        sessionRefs.computeIfAbsent(conversationId, k -> new ArrayList<>());
        return conversationId;
    }

    public List<PptReferenceDoc> listRefs(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return List.copyOf(sessionRefs.getOrDefault(conversationId, List.of()));
    }

    public List<Map<String, Object>> listRefSummaries(String conversationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PptReferenceDoc ref : listRefs(conversationId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("refId", ref.getRefId());
            m.put("fileName", ref.getFileName());
            m.put("role", ref.getRole());
            m.put("sourceType", ref.getSourceType());
            m.put("contentLength", ref.getContentLength());
            m.put("autoDowngraded", ref.isAutoDowngraded());
            m.put("sourceDocumentId", ref.getSourceDocumentId());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> attachFromUpload(String conversationId, MultipartFile file,
                                                String preferredRole, String username) {
        conversationId = ensureConversationId(conversationId);
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("请选择文件");
        }
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document";
        if (!isSupportedFileName(fileName)) {
            throw new RuntimeException("不支持该文件格式");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage());
        }
        String mime = file.getContentType() != null ? file.getContentType()
                : RagDocumentIndexer.resolveMimeType(null, fileName);
        return attachExtracted(conversationId, fileName, mime, data, preferredRole, username,
                PptReferenceDoc.SOURCE_UPLOAD, null);
    }

    public Map<String, Object> attachFromLibrary(String conversationId, String sourceDocumentId,
                                                 String preferredRole, String username, String userRole) {
        conversationId = ensureConversationId(conversationId);
        DocumentFile doc = documentBranchMapper.selectByDocumentIdAndBranch(sourceDocumentId, "main");
        if (doc == null) {
            throw new RuntimeException("文档不存在");
        }
        if (!ragImportService.canAccessLibraryDocument(doc, username, userRole)) {
            throw new RuntimeException("无权使用该文档");
        }
        String fileName = RagDocumentIndexer.ensureFileNameWithExtension(doc.getTitle(), doc.getFileType());
        byte[] data = fileService.downloadForDocument(doc.getCurrentFileId(), fileName);
        String mime = RagDocumentIndexer.resolveMimeType(doc.getFileType(), fileName);
        return attachExtracted(conversationId, fileName, mime, data, preferredRole, username,
                PptReferenceDoc.SOURCE_LIBRARY, sourceDocumentId);
    }

    public void removeRef(String conversationId, String refId) {
        List<PptReferenceDoc> refs = sessionRefs.get(conversationId);
        if (refs == null) {
            return;
        }
        PptReferenceDoc removed = null;
        Iterator<PptReferenceDoc> it = refs.iterator();
        while (it.hasNext()) {
            PptReferenceDoc ref = it.next();
            if (ref.getRefId().equals(refId)) {
                removed = ref;
                it.remove();
                break;
            }
        }
        if (removed != null && PptReferenceDoc.ROLE_SECONDARY.equals(removed.getRole())
                && removed.getRagDocumentId() != null) {
            try {
                knowledgeBase.removeDocument(removed.getRagDocumentId());
            } catch (Exception e) {
                log.warn("清理PPT次文档索引失败: {}", e.getMessage());
            }
        }
    }

    public void clearSession(String conversationId) {
        if (conversationId == null) {
            return;
        }
        List<PptReferenceDoc> refs = sessionRefs.remove(conversationId);
        if (refs == null) {
            return;
        }
        for (PptReferenceDoc ref : refs) {
            if (PptReferenceDoc.ROLE_SECONDARY.equals(ref.getRole()) && ref.getRagDocumentId() != null) {
                try {
                    knowledgeBase.removeDocument(ref.getRagDocumentId());
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 构建注入 system prompt 的主文档块（多轮对话全程可用）。
     */
    public String buildPrimaryContext(String conversationId) {
        List<PptReferenceDoc> refs = sessionRefs.getOrDefault(conversationId, List.of());
        StringBuilder sb = new StringBuilder();
        for (PptReferenceDoc ref : refs) {
            if (!PptReferenceDoc.ROLE_PRIMARY.equals(ref.getRole())) {
                continue;
            }
            if (ref.getFullText() == null || ref.getFullText().isBlank()) {
                continue;
            }
            sb.append("\n【主文档 - ").append(ref.getFileName()).append("】\n");
            sb.append(ref.getFullText()).append("\n");
        }
        if (sb.isEmpty()) {
            return "";
        }
        return "\n\n===== 主文档参考资料（全文，请优先据此组织PPT核心结构与观点） =====\n"
                + sb + "===== 主文档参考资料结束 =====\n";
    }

    /**
     * 生成 PPT 前，对次文档做知识库检索补充。
     */
    public String buildSecondaryContext(String conversationId, String query) {
        List<PptReferenceDoc> refs = sessionRefs.getOrDefault(conversationId, List.of());
        List<String> ragIds = refs.stream()
                .filter(r -> PptReferenceDoc.ROLE_SECONDARY.equals(r.getRole()))
                .map(PptReferenceDoc::getRagDocumentId)
                .filter(Objects::nonNull)
                .toList();
        if (ragIds.isEmpty()) {
            return "";
        }
        String searchQuery = query != null && !query.isBlank() ? query : "PPT主题要点";
        List<Map<String, Object>> hits = retrieveFromSecondaryDocs(searchQuery, ragIds, secondaryTopK);
        if (hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n===== 次文档检索补充（用于丰富PPT多样性，非核心依据） =====\n");
        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> hit = hits.get(i);
            String fileName = String.valueOf(hit.getOrDefault("fileName", "未知"));
            String content = String.valueOf(hit.getOrDefault("content", ""));
            if (content.isBlank()) {
                continue;
            }
            sb.append("【补充片段 ").append(i + 1).append(" - ").append(fileName).append("】\n");
            sb.append(content).append("\n\n");
        }
        sb.append("===== 次文档检索补充结束 =====\n");
        return sb.toString();
    }

    private List<Map<String, Object>> retrieveFromSecondaryDocs(String query, List<String> ragDocIds, int topK) {
        Set<String> allowed = new HashSet<>(ragDocIds);
        List<Map<String, Object>> raw;
        try {
            raw = knowledgeBase.hybridSearchWithRerank(query, topK * 3, Reranker.RerankStrategy.DASHSCOPE_RERANK);
        } catch (Exception e) {
            log.warn("PPT次文档Rerank检索失败，降级混合检索: {}", e.getMessage());
            raw = knowledgeBase.hybridSearch(query, topK * 3);
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> hit : raw) {
            String docId = resolveDocId(hit);
            if (docId != null && allowed.contains(docId)) {
                filtered.add(hit);
                if (filtered.size() >= topK) {
                    break;
                }
            }
        }
        if (!filtered.isEmpty()) {
            return filtered;
        }
        for (String docId : ragDocIds) {
            String content = knowledgeBase.getDocumentContent(docId);
            if (content != null && !content.isBlank()) {
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("fileName", knowledgeBase.getDocumentMetadata(docId).getOrDefault("fileName", docId));
                fallback.put("content", content.substring(0, Math.min(2000, content.length())));
                fallback.put("documentId", docId);
                filtered.add(fallback);
                if (filtered.size() >= topK) {
                    break;
                }
            }
        }
        return filtered;
    }

    private String resolveDocId(Map<String, Object> hit) {
        Object docId = hit.get("documentId");
        if (docId != null && !docId.toString().isBlank()) {
            return docId.toString();
        }
        Object chunkId = hit.get("chunkId");
        if (chunkId == null) {
            chunkId = hit.get("id");
        }
        if (chunkId == null) {
            return null;
        }
        String cid = chunkId.toString();
        int idx = cid.lastIndexOf("::");
        return idx > 0 ? cid.substring(0, idx) : cid;
    }

    private Map<String, Object> attachExtracted(String conversationId, String fileName, String mime,
                                                  byte[] data, String preferredRole, String username,
                                                  String sourceType, String sourceDocumentId) {
        String text = textExtractor.extract(mime, fileName, data);
        if (text == null || text.isBlank()) {
            throw new RuntimeException("文档内容为空，无法作为参考");
        }
        int length = text.length();
        String role = resolveRole(preferredRole, length);
        boolean autoDowngraded = wantsPrimary(preferredRole) && PptReferenceDoc.ROLE_SECONDARY.equals(role);

        PptReferenceDoc ref = new PptReferenceDoc();
        ref.setRefId(UUID.randomUUID().toString());
        ref.setFileName(RagDocumentIndexer.ensureFileNameWithExtension(fileName, mime));
        ref.setRole(role);
        ref.setSourceType(sourceType);
        ref.setContentLength(length);
        ref.setAutoDowngraded(autoDowngraded);
        ref.setSourceDocumentId(sourceDocumentId);

        if (PptReferenceDoc.ROLE_PRIMARY.equals(role)) {
            ref.setFullText(text);
        } else {
            String ragId = "ppt-" + conversationId.substring(0, Math.min(8, conversationId.length()))
                    + "-" + UUID.randomUUID();
            Map<String, Object> meta = new HashMap<>();
            meta.put("fileName", ref.getFileName());
            meta.put("sourceType", "ppt-secondary");
            meta.put("pptSessionId", conversationId);
            meta.put("indexedBy", username != null ? username : "system");
            if (sourceDocumentId != null) {
                meta.put("sourceDocumentId", sourceDocumentId);
            }
            ragDocumentIndexer.indexFromBytes(ragId, data, ref.getFileName(), mime, meta);
            ref.setRagDocumentId(ragId);
        }

        sessionRefs.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(ref);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conversationId);
        result.put("refId", ref.getRefId());
        result.put("fileName", ref.getFileName());
        result.put("role", ref.getRole());
        result.put("sourceType", ref.getSourceType());
        result.put("contentLength", ref.getContentLength());
        result.put("autoDowngraded", autoDowngraded);
        result.put("primaryMaxChars", primaryMaxChars);
        if (autoDowngraded) {
            result.put("message", "文档内容超过 " + primaryMaxChars + " 字，已自动作为次文档（检索补充）处理");
        } else if (PptReferenceDoc.ROLE_PRIMARY.equals(role)) {
            result.put("message", "已作为主文档，全文将注入参考资料");
        } else {
            result.put("message", "已作为次文档，生成时将检索相关内容补充");
        }
        log.info("PPT附加文档: session={}, file={}, role={}, chars={}", conversationId, ref.getFileName(), role, length);
        return result;
    }

    private String resolveRole(String preferredRole, int contentLength) {
        if (wantsSecondary(preferredRole)) {
            return PptReferenceDoc.ROLE_SECONDARY;
        }
        if (wantsPrimary(preferredRole) && contentLength > primaryMaxChars) {
            return PptReferenceDoc.ROLE_SECONDARY;
        }
        if ("auto".equalsIgnoreCase(preferredRole) || preferredRole == null || preferredRole.isBlank()) {
            return contentLength <= primaryMaxChars
                    ? PptReferenceDoc.ROLE_PRIMARY
                    : PptReferenceDoc.ROLE_SECONDARY;
        }
        return PptReferenceDoc.ROLE_PRIMARY;
    }

    private boolean wantsPrimary(String preferredRole) {
        return "primary".equalsIgnoreCase(preferredRole) || "主文档".equals(preferredRole);
    }

    private boolean wantsSecondary(String preferredRole) {
        return "secondary".equalsIgnoreCase(preferredRole) || "次文档".equals(preferredRole);
    }

    private boolean isSupportedFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || lower.endsWith(".md") || lower.endsWith(".txt")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".csv")
                || lower.endsWith(".html") || lower.endsWith(".htm")
                || FileUtils.getFileExtension(fileName) != null;
    }
}
