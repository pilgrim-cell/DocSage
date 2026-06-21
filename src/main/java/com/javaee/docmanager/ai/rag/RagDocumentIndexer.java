package com.javaee.docmanager.ai.rag;

import com.javaee.docmanager.file.util.FileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 知识库文档索引统一入口：本地上传与文档库导入走相同提取、分块、向量化流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDocumentIndexer {

    private final KnowledgeBase knowledgeBase;
    private final TextExtractor textExtractor;

    /**
     * 从原始文件字节索引到知识库（与「本地上传」逻辑一致）。
     */
    public void indexFromBytes(String documentId, byte[] rawData, String fileName, String mimeType,
                               Map<String, Object> extraMetadata) {
        if (rawData == null || rawData.length == 0) {
            throw new RuntimeException("文件数据为空，无法索引");
        }
        String normalizedName = ensureFileNameWithExtension(fileName, mimeType);
        String resolvedMime = resolveMimeType(mimeType, normalizedName);
        String ragFileType = deriveRagFileType(normalizedName, resolvedMime);

        log.info("知识库统一索引: documentId={}, fileName={}, mime={}, ragFileType={}, bytes={}",
                documentId, normalizedName, resolvedMime, ragFileType, rawData.length);

        String text = textExtractor.extract(resolvedMime, normalizedName, rawData);
        if (text == null || text.isBlank()) {
            String lower = normalizedName.toLowerCase();
            if (lower.endsWith(".pdf")) {
                throw new RuntimeException("PDF 无法提取文字内容，可能为扫描件。请使用可选中文字的 PDF");
            }
            throw new RuntimeException("文档内容为空，无法索引");
        }

        Map<String, Object> metadata = new HashMap<>();
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        metadata.put("fileName", normalizedName);
        metadata.put("fileType", ragFileType);

        knowledgeBase.indexDocumentWithMetadata(documentId, text, rawData, metadata);

        log.info("知识库统一索引完成: documentId={}, textLength={}, chunks={}",
                documentId, text.length(), knowledgeBase.countDocumentChunks(documentId));
    }

    public static String ensureFileNameWithExtension(String fileName, String mimeType) {
        String name = fileName != null && !fileName.isBlank() ? fileName.trim() : "document";
        if (FileUtils.getFileExtension(name) != null) {
            return name;
        }
        String ext = extensionFromMime(mimeType);
        return ext != null ? name + "." + ext : name;
    }

    public static String resolveMimeType(String mimeType, String fileName) {
        if (mimeType != null && mimeType.contains("/")) {
            return mimeType;
        }
        if (fileName == null) {
            return mimeType != null ? mimeType : "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    public static String deriveRagFileType(String fileName, String mimeType) {
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

    private static String extensionFromMime(String mimeType) {
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
}
