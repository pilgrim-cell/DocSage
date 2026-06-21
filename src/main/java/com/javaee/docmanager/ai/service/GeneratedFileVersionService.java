package com.javaee.docmanager.ai.service;

import com.javaee.docmanager.ai.entity.GeneratedFile;
import com.javaee.docmanager.ai.entity.GeneratedFileVersion;
import com.javaee.docmanager.ai.mapper.GeneratedFileMapper;
import com.javaee.docmanager.ai.mapper.GeneratedFileVersionMapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeneratedFileVersionService {

    private static final String BUCKET = "doc-ai-generated";
    private static final int MAX_VERSIONS = 15;

    private final GeneratedFileVersionMapper versionMapper;
    private final GeneratedFileMapper generatedFileMapper;
    private final MinioClient minioClient;

    public GeneratedFileVersion createInitialVersion(GeneratedFile file, byte[] htmlBytes, String title,
                                                       int sectionCount, String changeLog, String createBy) {
        return persistVersion(file.getId(), 1, htmlBytes, title, sectionCount, changeLog,
                GeneratedFileVersion.STATUS_APPLIED, createBy, true);
    }

    public GeneratedFileVersion createDraftVersion(String fileId, byte[] htmlBytes, String title,
                                                   int sectionCount, String changeLog, String createBy) {
        GeneratedFile file = generatedFileMapper.selectById(fileId);
        if (file == null) {
            throw new RuntimeException("文件不存在");
        }
        removeDraftVersions(fileId);
        int next = versionMapper.selectMaxVersionNumber(fileId) + 1;
        return persistVersion(fileId, next, htmlBytes, title, sectionCount, changeLog,
                GeneratedFileVersion.STATUS_DRAFT, createBy, false);
    }

    @Transactional
    public GeneratedFile applyVersion(String fileId, String versionId) {
        GeneratedFile file = generatedFileMapper.selectById(fileId);
        if (file == null) {
            throw new RuntimeException("文件不存在");
        }
        GeneratedFileVersion target = versionMapper.selectById(versionId);
        if (target == null || !fileId.equals(target.getFileId())) {
            throw new RuntimeException("版本不存在");
        }
        // 仅删除其他草稿；即将应用的草稿必须保留，否则 MinIO 对象会被删掉
        removeOtherDraftVersions(fileId, versionId);
        if (GeneratedFileVersion.STATUS_DRAFT.equals(target.getStatus())) {
            versionMapper.updateStatus(versionId, GeneratedFileVersion.STATUS_APPLIED);
        }
        generatedFileMapper.updateCurrentVersion(fileId, versionId, target.getTitle(), target.getObjectKey());
        log.info("PPT应用版本: fileId={}, version={}, objectKey={}",
                fileId, target.getVersionNumber(), target.getObjectKey());
        return generatedFileMapper.selectById(fileId);
    }

    @Transactional
    public GeneratedFile rollbackVersion(String fileId, String versionId, String operator) {
        GeneratedFile applied = applyVersion(fileId, versionId);
        GeneratedFileVersion target = versionMapper.selectById(versionId);
        if (target != null) {
            target.setChangeLog("回退到版本 v" + target.getVersionNumber()
                    + (operator != null ? "（" + operator + "）" : ""));
        }
        log.info("PPT回退版本: fileId={}, versionId={}", fileId, versionId);
        return applied;
    }

    public void discardDraft(String fileId) {
        List<GeneratedFileVersion> drafts = versionMapper.selectByFileId(fileId).stream()
                .filter(v -> GeneratedFileVersion.STATUS_DRAFT.equals(v.getStatus()))
                .toList();
        for (GeneratedFileVersion draft : drafts) {
            removeObjectQuietly(draft.getObjectKey());
            versionMapper.deleteById(draft.getId());
        }
    }

    public List<GeneratedFileVersion> listVersions(String fileId) {
        return versionMapper.selectByFileId(fileId);
    }

    public GeneratedFileVersion getDraft(String fileId) {
        return versionMapper.selectDraftByFileId(fileId);
    }

    public GeneratedFileVersion getVersion(String versionId) {
        return versionMapper.selectById(versionId);
    }

    public String resolvePreviewObjectKey(GeneratedFile file, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            GeneratedFileVersion version = versionMapper.selectById(versionId);
            if (version != null && file.getId().equals(version.getFileId())) {
                return version.getObjectKey();
            }
            throw new RuntimeException("版本不存在");
        }
        return resolveCurrentObjectKey(file);
    }

    public String resolveCurrentObjectKey(GeneratedFile file) {
        if (file.getCurrentVersionId() != null && !file.getCurrentVersionId().isBlank()) {
            GeneratedFileVersion current = versionMapper.selectById(file.getCurrentVersionId());
            if (current != null && objectExists(current.getObjectKey())) {
                return current.getObjectKey();
            }
            log.warn("当前版本对象不可用，尝试回退到最近可用版本: fileId={}", file.getId());
            for (GeneratedFileVersion v : versionMapper.selectByFileId(file.getId())) {
                if (GeneratedFileVersion.STATUS_APPLIED.equals(v.getStatus())
                        && objectExists(v.getObjectKey())) {
                    generatedFileMapper.updateCurrentVersion(
                            file.getId(), v.getId(), v.getTitle(), v.getObjectKey());
                    return v.getObjectKey();
                }
            }
        }
        if (file.getObjectKey() != null && !file.getObjectKey().isBlank()
                && objectExists(file.getObjectKey())) {
            return file.getObjectKey();
        }
        return file.getFileId();
    }

    private boolean objectExists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        try {
            minioClient.statObject(
                    io.minio.StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] readVersionBytes(String objectKey) {
        try (var is = minioClient.getObject(
                io.minio.GetObjectArgs.builder().bucket(BUCKET).object(objectKey).build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取版本文件失败: " + e.getMessage(), e);
        }
    }

    public void deleteAllVersions(String fileId) {
        for (GeneratedFileVersion version : versionMapper.selectByFileId(fileId)) {
            removeObjectQuietly(version.getObjectKey());
        }
        versionMapper.deleteByFileId(fileId);
    }

    private GeneratedFileVersion persistVersion(String fileId, int versionNumber, byte[] htmlBytes,
                                                String title, int sectionCount, String changeLog,
                                                String status, String createBy, boolean setCurrent) {
        trimVersionsIfNeeded(fileId);

        String versionId = UUID.randomUUID().toString();
        String objectKey = fileId + "/v" + versionNumber + ".html";

        uploadHtml(objectKey, htmlBytes);

        GeneratedFileVersion version = new GeneratedFileVersion();
        version.setId(versionId);
        version.setFileId(fileId);
        version.setVersionNumber(versionNumber);
        version.setTitle(title);
        version.setObjectKey(objectKey);
        version.setSectionCount(sectionCount);
        version.setChangeLog(changeLog);
        version.setStatus(status);
        version.setCreateBy(createBy != null ? createBy : "system");
        version.setCreateTime(new Date());
        versionMapper.insert(version);

        if (setCurrent) {
            generatedFileMapper.updateCurrentVersion(fileId, versionId, title, objectKey);
        }
        return version;
    }

    private void trimVersionsIfNeeded(String fileId) {
        while (versionMapper.countByFileId(fileId) >= MAX_VERSIONS) {
            GeneratedFileVersion oldest = versionMapper.selectOldestByFileId(fileId);
            if (oldest == null) {
                break;
            }
            GeneratedFile file = generatedFileMapper.selectById(fileId);
            if (file != null && oldest.getId().equals(file.getCurrentVersionId())) {
                break;
            }
            removeObjectQuietly(oldest.getObjectKey());
            versionMapper.deleteById(oldest.getId());
        }
    }

    private void removeDraftVersions(String fileId) {
        removeOtherDraftVersions(fileId, null);
    }

    private void removeOtherDraftVersions(String fileId, String exceptVersionId) {
        List<GeneratedFileVersion> all = versionMapper.selectByFileId(fileId);
        for (GeneratedFileVersion v : all) {
            if (!GeneratedFileVersion.STATUS_DRAFT.equals(v.getStatus())) {
                continue;
            }
            if (exceptVersionId != null && exceptVersionId.equals(v.getId())) {
                continue;
            }
            removeObjectQuietly(v.getObjectKey());
            versionMapper.deleteById(v.getId());
        }
    }

    private void uploadHtml(String objectKey, byte[] htmlBytes) {
        try (var is = new ByteArrayInputStream(htmlBytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(BUCKET)
                            .object(objectKey)
                            .stream(is, htmlBytes.length, -1)
                            .contentType("text/html; charset=utf-8")
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("版本文件上传失败: " + e.getMessage(), e);
        }
    }

    private void removeObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(BUCKET).object(objectKey).build()
            );
        } catch (Exception e) {
            log.warn("删除MinIO版本对象失败: {}", objectKey);
        }
    }
}
