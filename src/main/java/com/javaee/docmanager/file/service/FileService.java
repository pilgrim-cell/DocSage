package com.javaee.docmanager.file.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务接口
 */
public interface FileService {

    /**
     * 单文件上传（文件管理：仅图片和压缩包）
     */
    String upload(MultipartFile file);

    /**
     * 文档上传（文档管理：仅PDF/Word/PPT/CSV）
     */
    String uploadDocument(MultipartFile file);

    /**
     * 将字节内容作为文档上传到文档库（用于 AI 生成文件归档等）
     */
    String uploadDocumentBytes(byte[] data, String fileName, String contentType);

    /**
     * 多文件上传
     */
    String[] uploadMultiple(MultipartFile[] files);

    /**
     * 分片上传
     */
    void uploadChunk(MultipartFile chunk, String fileId, int chunkIndex, int totalChunks);

    /**
     * 分片合并
     */
    String mergeChunk(String fileId, String fileName);

    /**
     * 文件下载
     */
    byte[] download(String fileId);

    /**
     * 解析 MinIO/本地存储中的实际对象名（文档上传可能无 file_metadata）
     */
    String resolveStorageKey(String fileId);

    /**
     * 文件下载（根据文件名推断存储路径，适用于未保存元数据的文档文件）
     */
    byte[] downloadByName(String fileId, String fileName);

    /**
     * 文档库专用下载：优先按标题扩展名定位对象，避免扩展名探测顺序导致取错文件。
     */
    byte[] downloadForDocument(String fileId, String fileName);

    void delete(String fileId);

    /**
     * 文件重命名
     */
    void rename(String fileId, String newName);

    /**
     * 文件移动
     */
    void move(String fileId, String targetPath);

    /**
     * 文件复制
     */
    String copy(String fileId, String targetPath);

}
