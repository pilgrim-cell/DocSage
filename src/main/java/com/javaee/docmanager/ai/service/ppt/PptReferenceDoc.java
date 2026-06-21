package com.javaee.docmanager.ai.service.ppt;

import lombok.Data;

/**
 * PPT 生成会话中附加的参考文档。
 */
@Data
public class PptReferenceDoc {
    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_SECONDARY = "SECONDARY";
    public static final String SOURCE_UPLOAD = "upload";
    public static final String SOURCE_LIBRARY = "library";

    private String refId;
    private String fileName;
    /** PRIMARY 或 SECONDARY */
    private String role;
    /** upload 或 library */
    private String sourceType;
    /** 次文档在知识库中的 documentId；主文档为空 */
    private String ragDocumentId;
    /** 主文档全文；次文档为空 */
    private String fullText;
    private int contentLength;
    /** 用户请求主文档但因超长被降级 */
    private boolean autoDowngraded;
    private String sourceDocumentId;
}
