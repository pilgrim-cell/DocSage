package com.javaee.docmanager.doc.service;

import com.javaee.docmanager.doc.entity.DocumentFile;

import java.util.List;

public interface DocumentBranchService {

    List<DocumentFile> getBranches(String documentId);

    DocumentFile createBranch(String documentId, String sourceBranchName, String newBranchName, String username);

    DocumentFile mergeBranch(String documentId, String sourceBranch, String targetBranch, String username);

    void deleteBranch(String documentId, String branchName);
}
