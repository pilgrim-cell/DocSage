package com.javaee.docmanager.doc.service;

import com.javaee.docmanager.doc.entity.DocumentFile;
import com.javaee.docmanager.doc.entity.DocumentFileVersion;

import java.util.List;

public interface DocumentFileService {
    DocumentFile createDocument(String fileId, String title, String version, String fileType, String createBy, Long userId);
    DocumentFile uploadNewVersion(String documentId, String fileId, String version, String changeLog, String uploadedBy);
    List<DocumentFile> listAccessibleDocuments(Long ownerUserId);
    List<DocumentFileVersion> getVersions(String documentId);
    DocumentFile restoreVersion(String documentId, String versionId, String restoreBy);
    void deleteDocument(String documentId);
    DocumentFile getById(String id);
    DocumentFile requireAccessibleById(String id);
    DocumentFile requireAccessibleMainBranch(String documentId);
}
