package com.javaee.docmanager.doc.service.impl;

import com.javaee.docmanager.cache.CacheHelper;
import com.javaee.docmanager.common.constant.RedisKeyEnum;
import com.javaee.docmanager.doc.entity.DocumentFile;
import com.javaee.docmanager.doc.entity.DocumentFileVersion;
import com.javaee.docmanager.doc.mapper.DocumentBranchMapper;
import com.javaee.docmanager.doc.mapper.DocumentFileMapper;
import com.javaee.docmanager.doc.mapper.DocumentFileVersionMapper;
import com.javaee.docmanager.doc.service.DocumentBranchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentBranchServiceImpl implements DocumentBranchService {

    private final DocumentBranchMapper documentBranchMapper;
    private final DocumentFileMapper documentFileMapper;
    private final DocumentFileVersionMapper documentFileVersionMapper;
    private final CacheHelper cacheHelper;

    @Override
    public List<DocumentFile> getBranches(String documentId) {
        return documentBranchMapper.selectByDocumentId(documentId);
    }

    @Override
    @Transactional
    public DocumentFile createBranch(String documentId, String sourceBranchName, String newBranchName, String username) {
        validateBranchName(newBranchName);

        if ("main".equalsIgnoreCase(newBranchName)) {
            throw new RuntimeException("不能创建名为 main 的分支");
        }

        DocumentFile source = documentBranchMapper.selectByDocumentIdAndBranch(documentId, sourceBranchName);
        if (source == null) {
            throw new RuntimeException("源分支不存在: " + sourceBranchName);
        }

        DocumentFile existing = documentBranchMapper.selectByDocumentIdAndBranch(documentId, newBranchName);
        if (existing != null) {
            throw new RuntimeException("分支已存在: " + newBranchName);
        }

        String newBranchId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        DocumentFile branch = new DocumentFile();
        branch.setId(newBranchId);
        branch.setDocumentId(documentId);
        branch.setBranchName(newBranchName);
        branch.setTitle(source.getTitle());
        branch.setCurrentFileId(source.getCurrentFileId());
        branch.setCurrentVersion(source.getCurrentVersion());
        branch.setFileType(source.getFileType());
        branch.setStatus("active");
        branch.setCreateBy(username);
        branch.setUserId(source.getUserId());
        branch.setCreateTime(now);
        branch.setUpdateTime(now);
        documentFileMapper.insert(branch);

        List<DocumentFileVersion> sourceVersions = documentFileVersionMapper.selectByBranchId(source.getId());
        for (DocumentFileVersion sv : sourceVersions) {
            DocumentFileVersion copy = new DocumentFileVersion();
            copy.setId(UUID.randomUUID().toString());
            copy.setDocumentId(documentId);
            copy.setBranchId(newBranchId);
            copy.setFileId(sv.getFileId());
            copy.setVersion(sv.getVersion());
            copy.setChangeLog("从分支 " + sourceBranchName + " 复制: " + sv.getChangeLog());
            copy.setUploadedBy(username);
            copy.setUploadTime(now);
            documentFileVersionMapper.insert(copy);
        }

        evictDocCache(documentId);
        log.info("创建分支: documentId={}, {} -> {}", documentId, sourceBranchName, newBranchName);
        return branch;
    }

    @Override
    @Transactional
    public DocumentFile mergeBranch(String documentId, String sourceBranch, String targetBranch, String username) {
        if (sourceBranch.equals(targetBranch)) {
            throw new RuntimeException("源分支与目标分支不能相同");
        }

        DocumentFile source = documentBranchMapper.selectByDocumentIdAndBranch(documentId, sourceBranch);
        DocumentFile target = documentBranchMapper.selectByDocumentIdAndBranch(documentId, targetBranch);
        if (source == null || target == null) {
            throw new RuntimeException("分支不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        String mergeVersion = source.getCurrentVersion() + "-merge";

        DocumentFileVersion ver = new DocumentFileVersion();
        ver.setId(UUID.randomUUID().toString());
        ver.setDocumentId(documentId);
        ver.setBranchId(target.getId());
        ver.setFileId(source.getCurrentFileId());
        ver.setVersion(mergeVersion);
        ver.setChangeLog("合并分支 " + sourceBranch + " 到 " + targetBranch);
        ver.setUploadedBy(username);
        ver.setUploadTime(now);
        documentFileVersionMapper.insert(ver);

        documentFileMapper.updateCurrentFile(
                target.getId(),
                source.getCurrentFileId(),
                mergeVersion,
                now.toString()
        );

        evictDocCache(documentId);
        log.info("合并分支: documentId={}, {} -> {}", documentId, sourceBranch, targetBranch);
        return documentFileMapper.selectById(target.getId());
    }

    @Override
    @Transactional
    public void deleteBranch(String documentId, String branchName) {
        if ("main".equalsIgnoreCase(branchName)) {
            throw new RuntimeException("不能删除主分支 main");
        }

        DocumentFile branch = documentBranchMapper.selectByDocumentIdAndBranch(documentId, branchName);
        if (branch == null) {
            throw new RuntimeException("分支不存在: " + branchName);
        }

        documentBranchMapper.deleteByDocumentIdAndBranch(documentId, branchName);
        documentFileVersionMapper.deleteByBranchId(branch.getId());
        evictDocCache(documentId);
        log.info("删除分支: documentId={}, branch={}", documentId, branchName);
    }

    private void validateBranchName(String branchName) {
        if (branchName == null || branchName.isBlank()) {
            throw new RuntimeException("分支名不能为空");
        }
        if (!branchName.matches("^[a-zA-Z0-9_\\-]{1,32}$")) {
            throw new RuntimeException("分支名仅允许字母、数字、下划线和连字符");
        }
    }

    private void evictDocCache(String documentId) {
        cacheHelper.deleteAfterUpdate(
                RedisKeyEnum.DOC_FILE_LIST.getKey(),
                RedisKeyEnum.DOC_FILE.getKey(documentId),
                RedisKeyEnum.DOC_FILE_VERSIONS.getKey(documentId)
        );
    }
}
