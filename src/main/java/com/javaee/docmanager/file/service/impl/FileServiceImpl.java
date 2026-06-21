package com.javaee.docmanager.file.service.impl;

import com.javaee.docmanager.doc.service.DocumentFileService;
import com.javaee.docmanager.ai.rag.TextExtractor;
import com.javaee.docmanager.ai.rag.KnowledgeBase;
import com.javaee.docmanager.cache.CacheHelper;
import com.javaee.docmanager.common.constant.RedisKeyEnum;
import com.javaee.docmanager.file.config.FileStorageConfig;
import com.javaee.docmanager.file.config.MinioConfig;
import com.javaee.docmanager.file.service.FileMetadataService;
import com.javaee.docmanager.file.service.FileService;
import com.javaee.docmanager.file.util.FileUtils;
import com.javaee.docmanager.file.util.Md5Utils;
import com.javaee.docmanager.file.util.PathUtils;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.StatObjectArgs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 文件服务实现类
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    @Autowired
    private FileStorageConfig fileStorageConfig;

    @Autowired
    private FileMetadataService fileMetadataService;

    @Autowired
    private DocumentFileService documentFileService;

    @Autowired
    private MinioClient minioClient;

    @Autowired(required = false)
    private TextExtractor textExtractor;

    @Autowired(required = false)
    private KnowledgeBase knowledgeBase;

    @Autowired
    private CacheHelper cacheHelper;

    // 用于存储分片上传的临时文件
    private final ConcurrentMap<String, ConcurrentMap<Integer, File>> chunkMap = new ConcurrentHashMap<>();

    /** 文档上传不写 file_metadata，对象名为 fileId + 扩展名 */
    private static final String[] STORAGE_EXTENSIONS = {
            ".pdf", ".docx", ".doc", ".pptx", ".ppt", ".csv", ".md", ".html", ".htm",
            ".txt", ".jpg", ".png", ".jpeg", ".zip", ""
    };

    @Override
    public String upload(MultipartFile file) {
        try {
            // 校验文件类型：仅允许图片和压缩包
            String contentType = file.getContentType();
            if (!isFileType(contentType)) {
                throw new RuntimeException("文件管理仅支持图片和压缩包类型，请使用文档管理上传PDF/Word/PPT/CSV文件");
            }

            // 生成文件ID
            String fileId = UUID.randomUUID().toString();
            String fileName = file.getOriginalFilename();
            String fileExtension = FileUtils.getFileExtension(fileName);
            String storageFileName = fileId + (fileExtension != null ? "." + fileExtension : "");

            // 根据存储类型上传文件
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储
            Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
            System.out.println("存储路径: " + storagePath);
            try {
                // 确保存储目录存在
                Path storageDir = storagePath.getParent();
                if (storageDir != null) {
                    if (!Files.exists(storageDir)) {
                        Files.createDirectories(storageDir);
                        System.out.println("目录创建成功: " + storageDir);
                    } else {
                        System.out.println("目录已存在: " + storageDir);
                    }
                }
                
                // 使用FileOutputStream保存文件
                File destFile = storagePath.toFile();
                System.out.println("目标文件: " + destFile.getAbsolutePath());
                System.out.println("目标文件是否存在: " + destFile.exists());
                System.out.println("目标文件是否可写: " + destFile.canWrite());
                
                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = file.getInputStream().read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                    fos.flush();
                    System.out.println("文件上传成功: " + storagePath);
                }
            } catch (Exception e) {
                System.out.println("本地存储错误: " + e.getMessage());
                e.printStackTrace();
                throw e;
            }
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储
                System.out.println("=== 开始MinIO存储 ===");
                System.out.println("存储桶名称: " + fileStorageConfig.getBucketName());
                System.out.println("存储文件名: " + storageFileName);
                System.out.println("文件大小: " + file.getSize());
                System.out.println("文件类型: " + file.getContentType());
                try {
                    ensureBucketExists(fileStorageConfig.getBucketName());
                    System.out.println("存储桶检查/创建成功");
                    try (InputStream inputStream = file.getInputStream()) {
                        System.out.println("获取文件输入流成功");
                        minioClient.putObject(
                                PutObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(storageFileName)
                                        .stream(inputStream, file.getSize(), -1)
                                        .contentType(file.getContentType())
                                        .build()
                        );
                        System.out.println("文件上传到MinIO成功");
                    }
                } catch (Exception e) {
                    System.out.println("MinIO存储错误: " + e.getMessage());
                    e.printStackTrace();
                    throw e;
                }
            }

            // 保存文件元数据（容错处理，数据库不可用时继续执行）
            try {
                com.javaee.docmanager.file.entity.FileMetadata fileMetadata = new com.javaee.docmanager.file.entity.FileMetadata();
                fileMetadata.setId(fileId);
                fileMetadata.setFileId(fileId);
                fileMetadata.setFileName(fileName);
                fileMetadata.setOriginalFileName(fileName);
                fileMetadata.setFilePath(fileStorageConfig.getLocalPath());
                fileMetadata.setFileType(file.getContentType());
                fileMetadata.setFileSize(file.getSize());
                fileMetadata.setStorageType(fileStorageConfig.getStorageType());
                fileMetadata.setBucketName(fileStorageConfig.getBucketName());
                fileMetadata.setObjectKey(storageFileName);
                fileMetadata.setCreateBy("system");
                fileMetadataService.saveMetadata(fileMetadata);
            } catch (Exception e) {
                // 数据库不可用时，继续执行，只记录日志
                System.out.println("数据库不可用，跳过元数据保存: " + e.getMessage());
            }

            // 删除缓存
            cacheHelper.deleteAfterUpdate(RedisKeyEnum.FILE_LIST.getKey());

            return fileId;
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String uploadDocument(MultipartFile file) {
        // 校验文档类型：仅允许PDF/Word/PPT/CSV/MD
        String contentType = file.getContentType();
        String fileName = file.getOriginalFilename();
        if (!isDocumentType(contentType, fileName)) {
            throw new RuntimeException("文档管理仅支持 PDF、Word、PPT、CSV、Markdown 和 HTML 类型文件");
        }
        // 保存 file_metadata，确保知识库/预览等能精确定位 MinIO 对象
        return uploadInternal(file, true);
    }

    @Override
    public String uploadDocumentBytes(byte[] data, String fileName, String contentType) {
        if (data == null || data.length == 0) {
            throw new RuntimeException("文件内容为空");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new RuntimeException("文件名不能为空");
        }
        String mime = contentType != null && !contentType.isBlank()
                ? contentType : FileUtils.getContentType(fileName);
        if (!isDocumentType(mime, fileName)) {
            throw new RuntimeException("文档管理不支持该文件类型");
        }
        // 仅进入文档库，不写 file_metadata，避免出现在「文件管理」列表
        return uploadBytesInternal(data, fileName, mime, false);
    }

    /**
     * 内部上传方法（不含类型校验）
     */
    private String uploadInternal(MultipartFile file) {
        return uploadInternal(file, true);
    }

    private String uploadInternal(MultipartFile file, boolean saveMetadata) {
        try {
            String fileId = UUID.randomUUID().toString();
            String fileName = file.getOriginalFilename();
            String fileExtension = FileUtils.getFileExtension(fileName);
            String storageFileName = fileId + (fileExtension != null ? "." + fileExtension : "");
            String contentType = file.getContentType();

            if ("local".equals(fileStorageConfig.getStorageType())) {
                Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                Path storageDir = storagePath.getParent();
                if (storageDir != null && !Files.exists(storageDir)) {
                    Files.createDirectories(storageDir);
                }
                try (FileOutputStream fos = new FileOutputStream(storagePath.toFile())) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = file.getInputStream().read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                    fos.flush();
                }
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                ensureBucketExists(fileStorageConfig.getBucketName());
                try (InputStream inputStream = file.getInputStream()) {
                    minioClient.putObject(
                            PutObjectArgs.builder()
                                    .bucket(fileStorageConfig.getBucketName())
                                    .object(storageFileName)
                                    .stream(inputStream, file.getSize(), -1)
                                    .contentType(file.getContentType())
                                    .build()
                    );
                }
            }

            // 保存元数据（仅文件列表中的文件保存，文档有自己的表）
            if (saveMetadata) {
                try {
                    com.javaee.docmanager.file.entity.FileMetadata fileMetadata = new com.javaee.docmanager.file.entity.FileMetadata();
                    fileMetadata.setId(fileId);
                    fileMetadata.setFileId(fileId);
                    fileMetadata.setFileName(fileName);
                    fileMetadata.setOriginalFileName(fileName);
                    fileMetadata.setFilePath(fileStorageConfig.getLocalPath());
                    fileMetadata.setFileType(contentType);
                    fileMetadata.setFileSize(file.getSize());
                    fileMetadata.setStorageType(fileStorageConfig.getStorageType());
                    fileMetadata.setBucketName(fileStorageConfig.getBucketName());
                    fileMetadata.setObjectKey(storageFileName);
                    String createBy = "system";
                    try {
                        createBy = com.javaee.docmanager.security.UserContext.getCurrentUsername();
                        if (createBy == null) createBy = "system";
                    } catch (Exception ignored) {}
                    fileMetadata.setCreateBy(createBy);
                    fileMetadataService.saveMetadata(fileMetadata);
                } catch (Exception e) {
                    System.out.println("数据库不可用，跳过元数据保存: " + e.getMessage());
                }
            }

            // 自动索引已移至 DocumentController，仅文档列表中的文档会被索引

            return fileId;
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    private String uploadBytesInternal(byte[] data, String fileName, String contentType, boolean saveMetadata) {
        try {
            String fileId = UUID.randomUUID().toString();
            String fileExtension = FileUtils.getFileExtension(fileName);
            String storageFileName = fileId + (fileExtension != null ? "." + fileExtension : "");

            if ("local".equals(fileStorageConfig.getStorageType())) {
                Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                Path storageDir = storagePath.getParent();
                if (storageDir != null && !Files.exists(storageDir)) {
                    Files.createDirectories(storageDir);
                }
                Files.write(storagePath, data);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                ensureBucketExists(fileStorageConfig.getBucketName());
                try (InputStream inputStream = new ByteArrayInputStream(data)) {
                    minioClient.putObject(
                            PutObjectArgs.builder()
                                    .bucket(fileStorageConfig.getBucketName())
                                    .object(storageFileName)
                                    .stream(inputStream, data.length, -1)
                                    .contentType(contentType)
                                    .build()
                    );
                }
            }

            if (saveMetadata) {
                try {
                    com.javaee.docmanager.file.entity.FileMetadata fileMetadata = new com.javaee.docmanager.file.entity.FileMetadata();
                    fileMetadata.setId(fileId);
                    fileMetadata.setFileId(fileId);
                    fileMetadata.setFileName(fileName);
                    fileMetadata.setOriginalFileName(fileName);
                    fileMetadata.setFilePath(fileStorageConfig.getLocalPath());
                    fileMetadata.setFileType(contentType);
                    fileMetadata.setFileSize((long) data.length);
                    fileMetadata.setStorageType(fileStorageConfig.getStorageType());
                    fileMetadata.setBucketName(fileStorageConfig.getBucketName());
                    fileMetadata.setObjectKey(storageFileName);
                    String createBy = "system";
                    try {
                        createBy = com.javaee.docmanager.security.UserContext.getCurrentUsername();
                        if (createBy == null) createBy = "system";
                    } catch (Exception ignored) {}
                    fileMetadata.setCreateBy(createBy);
                    fileMetadataService.saveMetadata(fileMetadata);
                } catch (Exception e) {
                    System.out.println("数据库不可用，跳过元数据保存: " + e.getMessage());
                }
            }
            return fileId;
        } catch (Exception e) {
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String[] uploadMultiple(MultipartFile[] files) {
        String[] fileIds = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            fileIds[i] = upload(files[i]);
        }
        return fileIds;
    }

    @Override
    public void uploadChunk(MultipartFile chunk, String fileId, int chunkIndex, int totalChunks) {
        try {
            // 确保文件ID对应的分片映射存在
            chunkMap.computeIfAbsent(fileId, k -> new ConcurrentHashMap<>());
            ConcurrentMap<Integer, File> chunks = chunkMap.get(fileId);

            // 保存分片文件
            File chunkFile = File.createTempFile("chunk_" + fileId + "_", null);
            chunk.transferTo(chunkFile);
            chunks.put(chunkIndex, chunkFile);
        } catch (Exception e) {
            throw new RuntimeException("分片上传失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String mergeChunk(String fileId, String fileName) {
        try {
            // 获取分片文件
            ConcurrentMap<Integer, File> chunks = chunkMap.get(fileId);
            if (chunks == null || chunks.isEmpty()) {
                throw new RuntimeException("没有找到分片文件");
            }

            // 生成存储文件名
            String fileExtension = FileUtils.getFileExtension(fileName);
            String storageFileName = fileId + (fileExtension != null ? "." + fileExtension : "");
            byte[] mergedBytes = null;

            // 根据存储类型合并分片
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储合并
                Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                Files.createDirectories(storagePath.getParent());
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                // 按顺序读取分片并合并
                for (int i = 0; i < chunks.size(); i++) {
                    File chunkFile = chunks.get(i);
                    if (chunkFile != null) {
                        byte[] chunkBytes = Files.readAllBytes(chunkFile.toPath());
                        outputStream.write(chunkBytes);
                        // 删除临时分片文件
                        chunkFile.delete();
                    }
                }

                mergedBytes = outputStream.toByteArray();
                Files.write(storagePath, mergedBytes);
                outputStream.close();
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储合并（这里简化处理，实际应该使用MinIO的分片上传API）
                ensureBucketExists(fileStorageConfig.getBucketName());
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                // 按顺序读取分片并合并
                for (int i = 0; i < chunks.size(); i++) {
                    File chunkFile = chunks.get(i);
                    if (chunkFile != null) {
                        byte[] chunkBytes = Files.readAllBytes(chunkFile.toPath());
                        outputStream.write(chunkBytes);
                        // 删除临时分片文件
                        chunkFile.delete();
                    }
                }

                mergedBytes = outputStream.toByteArray();
                outputStream.close();

                // 上传合并后的文件
                try (InputStream inputStream = FileUtils.toInputStream(mergedBytes)) {
                    minioClient.putObject(
                            PutObjectArgs.builder()
                                    .bucket(fileStorageConfig.getBucketName())
                                    .object(storageFileName)
                                    .stream(inputStream, mergedBytes.length, -1)
                                    .contentType(FileUtils.getContentType(fileName))
                                    .build()
                    );
                }
            }

            // 清理分片映射
            chunkMap.remove(fileId);

            // 保存文件元数据
            try {
                com.javaee.docmanager.file.entity.FileMetadata fileMetadata = new com.javaee.docmanager.file.entity.FileMetadata();
                fileMetadata.setFileId(fileId);
                fileMetadata.setFileName(fileName);
                fileMetadata.setOriginalFileName(fileName);
                fileMetadata.setFilePath("minio:" + fileStorageConfig.getBucketName());
                fileMetadata.setFileType(com.javaee.docmanager.file.util.FileUtils.getContentType(fileName));
                fileMetadata.setFileSize(mergedBytes != null ? mergedBytes.length : 0);
                fileMetadata.setStorageType(fileStorageConfig.getStorageType());
                fileMetadata.setBucketName(fileStorageConfig.getBucketName());
                fileMetadata.setObjectKey(storageFileName);
                fileMetadata.setCreateBy("system");
                fileMetadataService.saveMetadata(fileMetadata);
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("数据库不可用，跳过元数据保存: " + e.getMessage());
            }

            // 删除缓存
            cacheHelper.deleteAfterUpdate(RedisKeyEnum.FILE_LIST.getKey());

            return fileId;
        } catch (Exception e) {
            // 清理分片文件
            if (chunkMap.containsKey(fileId)) {
                ConcurrentMap<Integer, File> chunks = chunkMap.get(fileId);
                chunks.values().forEach(File::delete);
                chunkMap.remove(fileId);
            }
            throw new RuntimeException("分片合并失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String resolveStorageKey(String fileId) {
        try {
            com.javaee.docmanager.file.entity.FileMetadata fileMetadata = fileMetadataService.getMetadata(fileId);
            if (fileMetadata != null && fileMetadata.getObjectKey() != null && !fileMetadata.getObjectKey().isBlank()) {
                return fileMetadata.getObjectKey();
            }
        } catch (Exception e) {
            log.warn("解析存储对象名时读取元数据失败: fileId={}", fileId);
        }
        for (String ext : STORAGE_EXTENSIONS) {
            String candidate = fileId + ext;
            if (storageObjectExists(candidate)) {
                return candidate;
            }
        }
        return fileId;
    }

    private boolean storageObjectExists(String storageFileName) {
        try {
            if ("local".equals(fileStorageConfig.getStorageType())) {
                return Files.exists(Paths.get(fileStorageConfig.getLocalPath(), storageFileName));
            }
            if ("minio".equals(fileStorageConfig.getStorageType())) {
                minioClient.statObject(
                        StatObjectArgs.builder()
                                .bucket(fileStorageConfig.getBucketName())
                                .object(storageFileName)
                                .build()
                );
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public byte[] download(String fileId) {
        try {
            String storageFileName = null;
            try {
                com.javaee.docmanager.file.entity.FileMetadata fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null && fileMetadata.getObjectKey() != null && !fileMetadata.getObjectKey().isBlank()) {
                    storageFileName = fileMetadata.getObjectKey();
                }
            } catch (Exception e) {
                log.warn("读取文件元数据失败，将按扩展名尝试定位对象: fileId={}, error={}", fileId, e.getMessage());
            }

            if (storageFileName != null) {
                try {
                    return readStorageObject(storageFileName);
                } catch (Exception e) {
                    log.warn("按 objectKey 下载失败，回退扩展名探测: fileId={}, objectKey={}", fileId, storageFileName);
                }
            }

            String resolvedKey = resolveStorageKey(fileId);
            byte[] data = readStorageObject(resolvedKey);
            repairObjectKeyIfNeeded(fileId, resolvedKey);
            return data;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("文件下载失败: " + e.getMessage(), e);
        }
    }

    private void repairObjectKeyIfNeeded(String fileId, String resolvedKey) {
        try {
            com.javaee.docmanager.file.entity.FileMetadata meta = fileMetadataService.getMetadata(fileId);
            if (meta != null && resolvedKey != null && !resolvedKey.equals(meta.getObjectKey())) {
                meta.setObjectKey(resolvedKey);
                fileMetadataService.updateMetadata(meta);
                cacheHelper.deleteAfterUpdate(RedisKeyEnum.FILE_METADATA.getKey(fileId));
                log.info("已修复文件 objectKey: fileId={}, objectKey={}", fileId, resolvedKey);
            }
        } catch (Exception ignored) {
        }
    }

    private byte[] downloadByProbingExtensions(String fileId) throws Exception {
        for (String ext : STORAGE_EXTENSIONS) {
            String candidate = fileId + ext;
            try {
                return readStorageObject(candidate);
            } catch (Exception ignored) {
                // 尝试下一个扩展名
            }
        }
        throw new RuntimeException("文件不存在: " + fileId);
    }

    private byte[] readStorageObject(String storageFileName) throws Exception {
        if ("local".equals(fileStorageConfig.getStorageType())) {
            Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
            if (!Files.exists(storagePath)) {
                throw new RuntimeException("文件不存在: " + storageFileName);
            }
            return Files.readAllBytes(storagePath);
        }
        if ("minio".equals(fileStorageConfig.getStorageType())) {
            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(fileStorageConfig.getBucketName())
                            .object(storageFileName)
                            .build()
            )) {
                return FileUtils.toByteArray(inputStream);
            }
        }
        throw new RuntimeException("不支持的存储类型: " + fileStorageConfig.getStorageType());
    }

    @Override
    public byte[] downloadForDocument(String fileId, String fileName) {
        if (fileId == null || fileId.isBlank()) {
            throw new RuntimeException("文件ID为空");
        }
        try {
            if (fileName != null && !fileName.isBlank()) {
                String ext = FileUtils.getFileExtension(fileName);
                if (ext != null) {
                    String keyed = fileId + "." + ext;
                    if (storageObjectExists(keyed)) {
                        log.info("文档库下载(按标题扩展名): fileId={}, object={}", fileId, keyed);
                        return readStorageObject(keyed);
                    }
                }
            }

            com.javaee.docmanager.file.entity.FileMetadata fileMetadata = fileMetadataService.getMetadata(fileId);
            if (fileMetadata != null && fileMetadata.getObjectKey() != null && !fileMetadata.getObjectKey().isBlank()) {
                log.info("文档库下载(元数据): fileId={}, object={}", fileId, fileMetadata.getObjectKey());
                return readStorageObject(fileMetadata.getObjectKey());
            }

            String storageKey = resolveStorageKey(fileId);
            log.info("文档库下载(探测): fileId={}, object={}", fileId, storageKey);
            return readStorageObject(storageKey);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("文档库文件下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] downloadByName(String fileId, String fileName) {
        try {
            String fileExtension = FileUtils.getFileExtension(fileName);
            String storageFileName = fileId + (fileExtension != null ? "." + fileExtension : "");

            if ("local".equals(fileStorageConfig.getStorageType())) {
                Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                return Files.readAllBytes(storagePath);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                try (InputStream inputStream = minioClient.getObject(
                        GetObjectArgs.builder()
                                .bucket(fileStorageConfig.getBucketName())
                                .object(storageFileName)
                                .build()
                )) {
                    return FileUtils.toByteArray(inputStream);
                }
            }
            throw new RuntimeException("不支持的存储类型: " + fileStorageConfig.getStorageType());
        } catch (Exception e) {
            throw new RuntimeException("文件下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String fileId) {
        try {
            // 尝试从数据库获取文件元数据
            com.javaee.docmanager.file.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;
            
            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                    System.out.println("从数据库获取到文件元数据，存储文件名: " + storageFileName);
                } else {
                    System.out.println("数据库中未找到文件元数据: " + fileId);
                }
            } catch (Exception e) {
                // 数据库不可用时，尝试不同的文件扩展名
                System.out.println("数据库不可用，尝试不同的文件扩展名: " + e.getMessage());
                // 尝试常见的文件扩展名
                String[] extensions = {".docx", ".pdf", ".txt", ".jpg", ".png", ".jpeg", ""};
                boolean deleted = false;
                for (String ext : extensions) {
                    try {
                        String tempFileName = fileId + ext;
                        System.out.println("尝试删除MinIO文件: " + tempFileName);
                        System.out.println("MinIO配置: bucket=" + fileStorageConfig.getBucketName());
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(tempFileName)
                                        .build()
                        );
                        deleted = true;
                        System.out.println("成功删除文件: " + tempFileName);
                        // 继续尝试其他扩展名，确保删除所有可能的文件
                    } catch (Exception ex) {
                        // 忽略错误，尝试下一个扩展名
                        System.out.println("尝试扩展名失败: " + ext + ", 错误: " + ex.getMessage());
                    }
                }
                if (deleted) {
                    return;
                } else {
                    throw new RuntimeException("文件不存在: " + fileId);
                }
            }
            
            // 如果有存储文件名，直接删除
            if (storageFileName != null) {
                System.out.println("使用存储文件名删除文件: " + storageFileName);
                if ("minio".equals(fileStorageConfig.getStorageType())) {
                    try {
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(storageFileName)
                                        .build()
                        );
                        System.out.println("成功删除文件: " + storageFileName);
                    } catch (Exception e) {
                        System.out.println("删除文件失败: " + e.getMessage());
                        throw new RuntimeException("文件删除失败: " + e.getMessage());
                    }
                } else if ("local".equals(fileStorageConfig.getStorageType())) {
                    try {
                        Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                        Files.deleteIfExists(storagePath);
                        System.out.println("成功删除本地文件: " + storagePath);
                    } catch (Exception e) {
                        System.out.println("删除本地文件失败: " + e.getMessage());
                        throw new RuntimeException("文件删除失败: " + e.getMessage());
                    }
                } else {
                    throw new RuntimeException("不支持的存储类型: " + fileStorageConfig.getStorageType());
                }
            } else {
                // 如果没有存储文件名，尝试使用fileId加不同扩展名删除
                System.out.println("没有存储文件名，尝试使用fileId加扩展名删除");
                String[] extensions = {".txt", ".docx", ".pdf", ".jpg", ".png", ".jpeg", ""};
                boolean deleted = false;
                for (String ext : extensions) {
                    String tempFileName = fileId + ext;
                    try {
                        if ("minio".equals(fileStorageConfig.getStorageType())) {
                            minioClient.removeObject(
                                    RemoveObjectArgs.builder()
                                            .bucket(fileStorageConfig.getBucketName())
                                            .object(tempFileName)
                                            .build()
                            );
                            System.out.println("成功删除MinIO文件: " + tempFileName);
                        } else if ("local".equals(fileStorageConfig.getStorageType())) {
                            Path storagePath = Paths.get(fileStorageConfig.getLocalPath(), tempFileName);
                            Files.deleteIfExists(storagePath);
                            System.out.println("成功删除本地文件: " + storagePath);
                        }
                        deleted = true;
                        break; // 删除成功后退出循环
                    } catch (Exception ex) {
                        // 忽略错误，尝试下一个扩展名
                        System.out.println("尝试删除失败: " + tempFileName + ", 错误: " + ex.getMessage());
                    }
                }
                if (!deleted) {
                    throw new RuntimeException("文件不存在: " + fileId);
                }
            }
            
            // 删除文件元数据
            try {
                fileMetadataService.deleteMetadata(fileId);
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("数据库不可用，跳过元数据删除: " + e.getMessage());
            }

            // 删除缓存
            cacheHelper.deleteAfterUpdate(
                    RedisKeyEnum.FILE_LIST.getKey(),
                    RedisKeyEnum.FILE_METADATA.getKey(fileId)
            );
        } catch (Exception e) {
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void rename(String fileId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new RuntimeException("新文件名不能为空");
        }
        newName = newName.trim();
        try {
            com.javaee.docmanager.file.entity.FileMetadata fileMetadata = fileMetadataService.getMetadata(fileId);
            if (fileMetadata == null) {
                throw new RuntimeException("文件不存在");
            }
            // 仅更新展示名称；MinIO 对象键保持 fileId.ext 不变，避免重命名后找不到文件
            String oldExt = FileUtils.getFileExtension(fileMetadata.getFileName());
            if (FileUtils.getFileExtension(newName) == null && oldExt != null) {
                newName = newName + "." + oldExt;
            }
            fileMetadata.setFileName(newName);
            fileMetadata.setOriginalFileName(newName);
            fileMetadataService.updateMetadata(fileMetadata);

            cacheHelper.deleteAfterUpdate(
                    RedisKeyEnum.FILE_LIST.getKey(),
                    RedisKeyEnum.FILE_METADATA.getKey(fileId)
            );
            log.info("文件重命名(仅元数据): fileId={}, newName={}, objectKey={}",
                    fileId, newName, fileMetadata.getObjectKey());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("文件重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void move(String fileId, String targetPath) {
        try {
            // 尝试从数据库获取文件元数据
            com.javaee.docmanager.file.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;
            
            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                    System.out.println("从数据库获取到文件元数据，存储文件名: " + storageFileName);
                } else {
                    System.out.println("数据库中未找到文件元数据: " + fileId);
                }
            } catch (Exception e) {
                // 数据库不可用时，使用fileId作为默认值
                System.out.println("数据库不可用: " + e.getMessage());
                storageFileName = fileId;
            }

            // 如果没有存储文件名，使用fileId作为默认值
            if (storageFileName == null) {
                storageFileName = fileId;
            }

            // 根据存储类型移动文件
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储
                Path oldPath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                Path newPath = Paths.get(targetPath, storageFileName);
                Files.createDirectories(newPath.getParent());
                Files.move(oldPath, newPath);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储（先复制再删除）
                try {
                    // 尝试不同的文件扩展名找到原文件
                    String[] extensions = {"", ".txt", ".docx", ".pdf", ".jpg", ".png", ".jpeg"};
                    String foundStorageFileName = null;
                    
                    for (String ext : extensions) {
                        try {
                            String tempFileName = storageFileName + ext;
                            // 检查文件是否存在
                            minioClient.statObject(
                                    StatObjectArgs.builder()
                                            .bucket(fileStorageConfig.getBucketName())
                                            .object(tempFileName)
                                            .build()
                            );
                            foundStorageFileName = tempFileName;
                            break;
                        } catch (Exception ex) {
                            // 忽略错误，尝试下一个扩展名
                        }
                    }
                    
                    if (foundStorageFileName != null) {
                        // 构建新的存储路径
                        String newStoragePath = targetPath + "/" + foundStorageFileName;
                        // 移除开头的斜杠
                        if (newStoragePath.startsWith("/")) {
                            newStoragePath = newStoragePath.substring(1);
                        }
                        
                        // 复制文件
                        minioClient.copyObject(
                                CopyObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(newStoragePath)
                                        .source(
                                                CopySource.builder()
                                                        .bucket(fileStorageConfig.getBucketName())
                                                        .object(foundStorageFileName)
                                                        .build()
                                        )
                                        .build()
                        );
                        // 删除原文件
                        minioClient.removeObject(
                                RemoveObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(foundStorageFileName)
                                        .build()
                        );
                        System.out.println("MinIO文件移动成功: " + foundStorageFileName + " -> " + newStoragePath);
                    } else {
                        throw new RuntimeException("文件不存在: " + storageFileName);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("MinIO文件移动失败: " + e.getMessage(), e);
                }
            }

            // 更新文件元数据（如果数据库可用）
            try {
                if (fileMetadata != null) {
                    fileMetadata.setFilePath(targetPath);
                    fileMetadataService.updateMetadata(fileMetadata);
                }
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("更新文件元数据失败: " + e.getMessage());
            }

            // 删除缓存
            cacheHelper.deleteAfterUpdate(
                    RedisKeyEnum.FILE_METADATA.getKey(fileId)
            );
        } catch (Exception e) {
            throw new RuntimeException("文件移动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String copy(String fileId, String targetPath) {
        try {
            // 尝试从数据库获取文件元数据
            com.javaee.docmanager.file.entity.FileMetadata fileMetadata = null;
            String storageFileName = null;
            String fileName = "copy_" + fileId + ".txt";
            
            try {
                fileMetadata = fileMetadataService.getMetadata(fileId);
                if (fileMetadata != null) {
                    storageFileName = fileMetadata.getObjectKey();
                    fileName = fileMetadata.getFileName();
                    System.out.println("从数据库获取到文件元数据，存储文件名: " + storageFileName);
                } else {
                    System.out.println("数据库中未找到文件元数据: " + fileId);
                }
            } catch (Exception e) {
                // 数据库不可用时，使用fileId作为默认值
                System.out.println("数据库不可用: " + e.getMessage());
                storageFileName = fileId;
            }

            // 如果没有存储文件名，使用fileId作为默认值
            if (storageFileName == null) {
                storageFileName = fileId;
            }

            // 生成新的文件ID
            String newFileId = UUID.randomUUID().toString();
            String newFileExtension = FileUtils.getFileExtension(fileName);
            String newStorageFileName = newFileId + (newFileExtension != null ? "." + newFileExtension : ".txt");

            // 根据存储类型复制文件
            if ("local".equals(fileStorageConfig.getStorageType())) {
                // 本地存储
                Path oldPath = Paths.get(fileStorageConfig.getLocalPath(), storageFileName);
                Path newPath = Paths.get(targetPath, newStorageFileName);
                Files.createDirectories(newPath.getParent());
                Files.copy(oldPath, newPath);
            } else if ("minio".equals(fileStorageConfig.getStorageType())) {
                // MinIO存储
                try {
                    // 尝试不同的文件扩展名找到原文件
                    String[] extensions = {"", ".txt", ".docx", ".pdf", ".jpg", ".png", ".jpeg"};
                    String foundStorageFileName = null;
                    
                    for (String ext : extensions) {
                        try {
                            String tempFileName = storageFileName + ext;
                            // 检查文件是否存在
                            minioClient.statObject(
                                    StatObjectArgs.builder()
                                            .bucket(fileStorageConfig.getBucketName())
                                            .object(tempFileName)
                                            .build()
                            );
                            foundStorageFileName = tempFileName;
                            break;
                        } catch (Exception ex) {
                            // 忽略错误，尝试下一个扩展名
                        }
                    }
                    
                    if (foundStorageFileName != null) {
                        // 构建新的存储路径
                        String newStoragePath = targetPath + "/" + newStorageFileName;
                        // 移除开头的斜杠
                        if (newStoragePath.startsWith("/")) {
                            newStoragePath = newStoragePath.substring(1);
                        }
                        
                        // 复制文件
                        minioClient.copyObject(
                                CopyObjectArgs.builder()
                                        .bucket(fileStorageConfig.getBucketName())
                                        .object(newStoragePath)
                                        .source(
                                                CopySource.builder()
                                                        .bucket(fileStorageConfig.getBucketName())
                                                        .object(foundStorageFileName)
                                                        .build()
                                        )
                                        .build()
                        );
                        System.out.println("MinIO文件复制成功: " + foundStorageFileName + " -> " + newStoragePath);
                    } else {
                        throw new RuntimeException("文件不存在: " + storageFileName);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("MinIO文件复制失败: " + e.getMessage(), e);
                }
            }

            // 保存新文件的元数据（如果数据库可用）
            try {
                if (fileMetadata != null) {
                    com.javaee.docmanager.file.entity.FileMetadata newFileMetadata = new com.javaee.docmanager.file.entity.FileMetadata();
                    newFileMetadata.setFileId(newFileId);
                    newFileMetadata.setFileName(fileName);
                    newFileMetadata.setOriginalFileName(fileName);
                    newFileMetadata.setFilePath(targetPath);
                    newFileMetadata.setFileType(fileMetadata.getFileType());
                    newFileMetadata.setFileSize(fileMetadata.getFileSize());
                    newFileMetadata.setStorageType(fileMetadata.getStorageType());
                    newFileMetadata.setBucketName(fileMetadata.getBucketName());
                    newFileMetadata.setObjectKey(newStorageFileName);
                    newFileMetadata.setCreateBy("system");
                    fileMetadataService.saveMetadata(newFileMetadata);
                }
            } catch (Exception e) {
                // 数据库不可用时，忽略错误
                System.out.println("保存新文件元数据失败: " + e.getMessage());
            }

            // 删除缓存
            cacheHelper.deleteAfterUpdate(RedisKeyEnum.FILE_LIST.getKey());

            return newFileId;
        } catch (Exception e) {
            throw new RuntimeException("文件复制失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确保MinIO存储桶存在
     */
    private void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }

    /**
     * 判断是否为文件类型（图片和压缩包）
     */
    private boolean isFileType(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase();
        if (type.contains("markdown")) return false;
        return type.startsWith("image/")
                || type.contains("zip") || type.contains("rar") || type.contains("7z")
                || type.contains("tar") || type.contains("gzip") || type.contains("compressed");
    }

    /**
     * 判断是否为文档类型（PDF/Word/PPT/CSV/MD）
     */
    private boolean isDocumentType(String contentType, String fileName) {
        if (contentType != null) {
            String type = contentType.toLowerCase();
            if (type.contains("pdf") || type.contains("word") || type.contains("msword")
                    || type.contains("csv") || type.contains("markdown")
                    || type.contains("powerpoint") || type.contains("presentation")
                    || type.contains("html")) {
                return true;
            }
        }
        if (fileName != null) {
            String name = fileName.toLowerCase();
            if (name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")
                    || name.endsWith(".ppt") || name.endsWith(".pptx")
                    || name.endsWith(".csv") || name.endsWith(".md")
                    || name.endsWith(".html") || name.endsWith(".htm")) {
                return true;
            }
        }
        return false;
    }

}
