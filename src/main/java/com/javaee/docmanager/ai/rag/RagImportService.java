package com.javaee.docmanager.ai.rag;

import com.javaee.docmanager.doc.entity.DocumentFile;
import com.javaee.docmanager.doc.mapper.DocumentBranchMapper;
import com.javaee.docmanager.doc.service.DocumentFileService;
import com.javaee.docmanager.file.service.FileService;
import com.javaee.docmanager.security.ResourceAccessService;
import com.javaee.docmanager.file.util.FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 从文档库导入知识库：权限校验、重复/过期状态、重新索引。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagImportService {

    public static final String LIB_RAG_PREFIX = "lib-";
    public static final String SYNC_INDEXED = "INDEXED";
    public static final String SYNC_OUTDATED = "OUTDATED";
    public static final String SYNC_NOT_INDEXED = "NOT_INDEXED";

    private final KnowledgeBase knowledgeBase;
    private final RagDocumentIndexer ragDocumentIndexer;
    private final DocumentFileService documentFileService;
    private final DocumentBranchMapper documentBranchMapper;
    private final FileService fileService;
    private final ResourceAccessService resourceAccessService;

    public List<Map<String, Object>> listImportCandidates(String username, String role) {
        List<DocumentFile> docs = documentFileService.listAccessibleDocuments(null);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (DocumentFile doc : docs) {
            if (!isSupportedDocument(doc)) {
                continue;
            }
            candidates.add(buildCandidateView(doc));
        }
        candidates.sort(Comparator.comparing(m -> String.valueOf(m.get("title")), String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    public Map<String, Object> importFromLibrary(String sourceDocumentId, String username, String role, boolean reindex) {
        DocumentFile doc = requireMainBranch(sourceDocumentId);
        assertLibraryAccess(doc, username, role);
        if (!isSupportedDocument(doc)) {
            throw new RuntimeException("该文档类型不支持建立知识库索引");
        }

        String ragDocumentId = resolveLibraryRagId(sourceDocumentId);
        Map<String, Object> existing = findLibraryIndexMeta(sourceDocumentId);
        boolean alreadyIndexed = existing != null && !existing.isEmpty();
        String existingRagId = alreadyIndexed
                ? stringVal(existing.getOrDefault("ragDocumentId", ragDocumentId))
                : ragDocumentId;
        if (existingRagId == null || existingRagId.isBlank()) {
            existingRagId = ragDocumentId;
        }

        if (alreadyIndexed && reindex) {
            knowledgeBase.purgeLibraryVectors(sourceDocumentId, existingRagId);
            alreadyIndexed = false;
        }

        if (alreadyIndexed && !reindex) {
            int healthyChunks = knowledgeBase.countDocumentChunks(existingRagId);
            if (healthyChunks > 0) {
                Map<String, Object> view = enrichRagDocument(existingRagId, existing);
                view.put("alreadyIndexed", true);
                view.put("message", "该文档已在知识库中，如需更新请选择重新索引");
                return view;
            }
            log.warn("文档库索引元数据存在但无切片，自动重新索引: ragId={}", existingRagId);
            knowledgeBase.removeDocument(existingRagId);
            removeLegacyLibraryIndex(sourceDocumentId);
            alreadyIndexed = false;
        }
        if (alreadyIndexed && !canAccessRagMetadata(existing, username, role)) {
            throw new RuntimeException("无权重新索引该知识库文档");
        }

        String fileName = RagDocumentIndexer.ensureFileNameWithExtension(doc.getTitle(), doc.getFileType());
        byte[] data = fileService.downloadForDocument(doc.getCurrentFileId(), fileName);
        String mimeType = RagDocumentIndexer.resolveMimeType(doc.getFileType(), fileName);

        Map<String, Object> metadata = buildLibraryMetadata(doc, username, ragDocumentId, fileName);
        knowledgeBase.purgeLibraryVectors(sourceDocumentId, ragDocumentId);
        ragDocumentIndexer.indexFromBytes(ragDocumentId, data, fileName, mimeType, metadata);

        int chunkCount = knowledgeBase.countDocumentChunks(ragDocumentId);
        String indexedText = knowledgeBase.getDocumentContent(ragDocumentId);
        if (chunkCount == 0 || indexedText == null || indexedText.isBlank()) {
            knowledgeBase.removeDocument(ragDocumentId);
            throw new RuntimeException("索引未完成：未生成可检索切片，请重试");
        }

        Map<String, Object> result = enrichRagDocument(ragDocumentId, knowledgeBase.getDocumentMetadata(ragDocumentId));
        result.put("chunkCount", chunkCount);
        result.put("reindexed", alreadyIndexed);
        result.put("message", alreadyIndexed ? "重新索引成功" : "导入成功");
        return result;
    }

    public Map<String, Object> batchImportFromLibrary(List<String> sourceDocumentIds, String username, String role, boolean reindex) {
        int success = 0;
        int skipped = 0;
        int failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (String sourceDocumentId : sourceDocumentIds) {
            try {
                DocumentFile doc = requireMainBranch(sourceDocumentId);
                assertLibraryAccess(doc, username, role);
                String ragDocumentId = resolveLibraryRagId(sourceDocumentId);
                Map<String, Object> existing = findLibraryIndexMeta(sourceDocumentId);
                boolean alreadyIndexed = existing != null && !existing.isEmpty();
                boolean doReindex = reindex;
                if (!doReindex && alreadyIndexed
                        && SYNC_OUTDATED.equals(computeSyncStatus(doc, existing))) {
                    doReindex = true;
                }
                Map<String, Object> r = importFromLibrary(sourceDocumentId, username, role, doReindex);
                if (Boolean.TRUE.equals(r.get("alreadyIndexed"))) {
                    skipped++;
                } else {
                    success++;
                }
                details.add(r);
            } catch (Exception e) {
                failed++;
                Map<String, Object> err = new HashMap<>();
                err.put("sourceDocumentId", sourceDocumentId);
                err.put("error", e.getMessage());
                details.add(err);
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("success", success);
        summary.put("skipped", skipped);
        summary.put("failed", failed);
        summary.put("details", details);
        return summary;
    }

    public List<Map<String, Object>> listIndexedDocuments(String username, String role) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String ragId : knowledgeBase.getAllDocumentIds()) {
            Map<String, Object> meta = knowledgeBase.getDocumentMetadata(ragId);
            if (meta == null || meta.isEmpty()) {
                continue;
            }
            if (!canAccessRagMetadata(meta, username, role)) {
                continue;
            }
            list.add(enrichRagDocument(ragId, meta));
        }
        list.sort((a, b) -> Long.compare(
                toLong(b.get("uploadTime")),
                toLong(a.get("uploadTime"))));
        return list;
    }

    private void attachIndexHealth(String ragDocumentId, Map<String, Object> view) {
        int chunkCount = knowledgeBase.countDocumentChunks(ragDocumentId);
        String content = knowledgeBase.getDocumentContent(ragDocumentId);
        int contentLength = content != null ? content.length() : 0;
        view.put("chunkCount", chunkCount);
        view.put("contentLength", contentLength);
        boolean healthy = chunkCount > 0 && contentLength > 0;
        view.put("indexHealthy", healthy);
        if (chunkCount == 0 || contentLength == 0) {
            view.put("syncStatus", "BROKEN");
            view.put("syncHint", contentLength == 0 && chunkCount > 0
                    ? "切片正文不可读，请点击重新索引"
                    : "索引数据不完整，请点击重新索引");
        }
    }

    public void assertCanDeleteRagDocument(String ragDocumentId, String username, String role) {
        assertCanAccessRagDocument(ragDocumentId, username, role);
    }

    public void assertCanAccessRagDocument(String ragDocumentId, String username, String role) {
        Map<String, Object> meta = knowledgeBase.getDocumentMetadata(ragDocumentId);
        if (meta == null || meta.isEmpty()) {
            return;
        }
        if (!canAccessRagMetadata(meta, username, role)) {
            throw new RuntimeException("无权访问该知识库文档");
        }
    }

    public static String resolveLibraryRagId(String sourceDocumentId) {
        return LIB_RAG_PREFIX + sourceDocumentId;
    }

    private Map<String, Object> findLibraryIndexMeta(String sourceDocumentId) {
        Map<String, Object> meta = knowledgeBase.getDocumentMetadata(resolveLibraryRagId(sourceDocumentId));
        if (meta != null && !meta.isEmpty()) {
            return meta;
        }
        return knowledgeBase.getDocumentMetadata("lib:" + sourceDocumentId);
    }

    private void removeLegacyLibraryIndex(String sourceDocumentId) {
        String legacyId = "lib:" + sourceDocumentId;
        Map<String, Object> legacy = knowledgeBase.getDocumentMetadata(legacyId);
        if (legacy != null && !legacy.isEmpty()) {
            knowledgeBase.removeDocument(legacyId);
        }
    }

    public boolean canAccessLibraryDocument(DocumentFile doc, String username, String role) {
        return resourceAccessService.canAccessByOwner(doc.getCreateBy(), doc.getUserId());
    }

    public boolean canAccessRagMetadata(Map<String, Object> meta, String username, String role) {
        if (resourceAccessService.isAdmin()) {
            return true;
        }
        String indexedBy = stringVal(meta.get("indexedBy"));
        return username != null && username.equals(indexedBy);
    }

    private void assertLibraryAccess(DocumentFile doc, String username, String role) {
        if (!canAccessLibraryDocument(doc, username, role)) {
            throw new RuntimeException("无权将该文档导入知识库");
        }
    }

    private DocumentFile requireMainBranch(String sourceDocumentId) {
        DocumentFile doc = documentBranchMapper.selectByDocumentIdAndBranch(sourceDocumentId, "main");
        if (doc == null) {
            doc = documentFileService.getById(sourceDocumentId);
        }
        if (doc == null || !"active".equalsIgnoreCase(String.valueOf(doc.getStatus()))) {
            throw new RuntimeException("文档不存在或已删除");
        }
        if (doc.getCurrentFileId() == null || doc.getCurrentFileId().isBlank()) {
            throw new RuntimeException("文档没有可索引的文件");
        }
        return doc;
    }

    private Map<String, Object> buildCandidateView(DocumentFile doc) {
        String sourceDocumentId = doc.getDocumentId() != null ? doc.getDocumentId() : doc.getId();
        String ragDocumentId = resolveLibraryRagId(sourceDocumentId);
        Map<String, Object> existing = findLibraryIndexMeta(sourceDocumentId);
        if (existing != null && !existing.isEmpty()) {
            String actualRagId = stringVal(existing.get("ragDocumentId"));
            if (actualRagId != null && !actualRagId.isBlank()) {
                ragDocumentId = actualRagId;
            }
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("sourceDocumentId", sourceDocumentId);
        item.put("ragDocumentId", ragDocumentId);
        item.put("title", doc.getTitle());
        item.put("fileType", doc.getFileType());
        item.put("currentVersion", doc.getCurrentVersion());
        item.put("createBy", doc.getCreateBy());
        item.put("updateTime", toEpochMillis(doc.getUpdateTime()));

        if (existing == null || existing.isEmpty()) {
            item.put("syncStatus", SYNC_NOT_INDEXED);
            item.put("indexed", false);
        } else {
            int chunkCount = knowledgeBase.countDocumentChunks(ragDocumentId);
            if (chunkCount == 0) {
                item.put("indexed", false);
                item.put("syncStatus", SYNC_OUTDATED);
                item.put("syncHint", "索引不完整，请重新索引");
            } else {
                item.put("indexed", true);
                item.put("syncStatus", computeSyncStatus(doc, existing));
                item.put("indexedAt", existing.get("uploadTime"));
                item.put("indexedVersion", existing.get("sourceVersion"));
            }
        }
        return item;
    }

    public Map<String, Object> enrichRagDocument(String ragDocumentId, Map<String, Object> meta) {
        Map<String, Object> view = new LinkedHashMap<>(meta);
        view.put("documentId", ragDocumentId);
        view.put("fileName", meta.getOrDefault("fileName", ragDocumentId));
        view.put("sourceType", meta.getOrDefault("sourceType", "upload"));

        String sourceDocumentId = stringVal(meta.get("sourceDocumentId"));
        view.put("syncStatus", SYNC_INDEXED);
        view.put("outdated", false);

        if (sourceDocumentId != null && !sourceDocumentId.isBlank()) {
            try {
                DocumentFile current = documentBranchMapper.selectByDocumentIdAndBranch(sourceDocumentId, "main");
                if (current != null) {
                    String status = computeSyncStatus(current, meta);
                    view.put("syncStatus", status);
                    view.put("outdated", SYNC_OUTDATED.equals(status));
                    if (SYNC_OUTDATED.equals(status)) {
                        view.put("syncHint", "文档库中的「" + current.getTitle() + "」已更新，需重新索引");
                    }
                }
            } catch (Exception e) {
                log.warn("检查文档同步状态失败: ragId={}, error={}", ragDocumentId, e.getMessage());
            }
        }
        attachIndexHealth(ragDocumentId, view);
        return view;
    }

    private String computeSyncStatus(DocumentFile current, Map<String, Object> indexedMeta) {
        String indexedFileId = stringVal(indexedMeta.get("sourceFileId"));
        String indexedVersion = stringVal(indexedMeta.get("sourceVersion"));
        long indexedUpdateAt = toLong(indexedMeta.get("sourceDocUpdatedAt"));

        boolean fileChanged = indexedFileId != null && current.getCurrentFileId() != null
                && !indexedFileId.equals(current.getCurrentFileId());
        boolean versionChanged = indexedVersion != null && current.getCurrentVersion() != null
                && !indexedVersion.equals(current.getCurrentVersion());
        long currentUpdateAt = toEpochMillis(current.getUpdateTime());
        boolean timeChanged = indexedUpdateAt > 0 && currentUpdateAt > indexedUpdateAt;

        if (fileChanged || versionChanged || timeChanged) {
            return SYNC_OUTDATED;
        }
        return SYNC_INDEXED;
    }

    private Map<String, Object> buildLibraryMetadata(DocumentFile doc, String username, String ragDocumentId, String fileName) {
        String sourceDocumentId = doc.getDocumentId() != null ? doc.getDocumentId() : doc.getId();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", fileName);
        metadata.put("sourceType", "library");
        metadata.put("sourceDocumentId", sourceDocumentId);
        metadata.put("sourceBranchId", doc.getId());
        metadata.put("sourceFileId", doc.getCurrentFileId());
        metadata.put("sourceVersion", doc.getCurrentVersion());
        metadata.put("sourceDocUpdatedAt", toEpochMillis(doc.getUpdateTime()));
        metadata.put("indexedBy", username != null ? username : "system");
        metadata.put("libraryTitle", doc.getTitle());
        metadata.put("ragDocumentId", ragDocumentId);
        return metadata;
    }

    private String ensureFileNameWithExtension(String title, String mimeType) {
        String name = title != null && !title.isBlank() ? title.trim() : "document";
        if (FileUtils.getFileExtension(name) != null) {
            return name;
        }
        String ext = extensionFromMime(mimeType);
        return ext != null ? name + "." + ext : name;
    }

    private String extensionFromMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return null;
        }
        String lower = mimeType.toLowerCase();
        if (lower.contains("pdf")) return "pdf";
        if (lower.contains("word") || lower.contains("msword") || lower.contains("document")) return "docx";
        if (lower.contains("presentation") || lower.contains("powerpoint")) return "pptx";
        if (lower.contains("spreadsheet") || lower.contains("excel")) return "xlsx";
        if (lower.contains("csv")) return "csv";
        if (lower.contains("markdown")) return "md";
        if (lower.contains("html")) return "html";
        if (lower.contains("plain")) return "txt";
        return null;
    }

    private boolean isSupportedDocument(DocumentFile doc) {
        String title = doc.getTitle() != null ? doc.getTitle().toLowerCase() : "";
        String type = doc.getFileType() != null ? doc.getFileType().toLowerCase() : "";
        if (title.endsWith(".pdf") || type.contains("pdf")) return true;
        if (title.endsWith(".doc") || title.endsWith(".docx") || type.contains("word") || type.contains("msword")) return true;
        if (title.endsWith(".ppt") || title.endsWith(".pptx") || type.contains("presentation") || type.contains("powerpoint")) return true;
        if (title.endsWith(".xls") || title.endsWith(".xlsx") || type.contains("excel") || type.contains("spreadsheet")) return true;
        if (title.endsWith(".csv") || type.contains("csv")) return true;
        if (title.endsWith(".md") || title.endsWith(".txt") || type.contains("markdown") || type.contains("plain")) return true;
        if (title.endsWith(".html") || title.endsWith(".htm") || type.contains("html")) return true;
        return FileUtils.getFileExtension(doc.getTitle()) != null;
    }

    private String deriveFileType(String fileName, String mimeType) {
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".md")) return "Markdown";
            if (lower.endsWith(".txt")) return "TXT";
            if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "Word";
            if (lower.endsWith(".pdf")) return "PDF";
            if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "PPT";
            if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "Excel";
            if (lower.endsWith(".csv")) return "CSV";
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "HTML";
        }
        if (mimeType != null && !mimeType.isBlank()) {
            String lower = mimeType.toLowerCase();
            if (lower.contains("pdf")) return "PDF";
            if (lower.contains("word") || lower.contains("msword") || lower.contains("document")) return "Word";
            if (lower.contains("presentation") || lower.contains("powerpoint")) return "PPT";
            if (lower.contains("spreadsheet") || lower.contains("excel")) return "Excel";
            if (lower.contains("csv")) return "CSV";
            if (lower.contains("markdown")) return "Markdown";
            if (lower.contains("html")) return "HTML";
            if (lower.contains("plain")) return "TXT";
        }
        return "未知";
    }

    private static String stringVal(Object v) {
        return v == null ? null : v.toString();
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long toEpochMillis(LocalDateTime time) {
        if (time == null) return 0L;
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
