package com.javaee.docmanager.file.service;

import com.javaee.docmanager.common.model.PageResult;
import com.javaee.docmanager.file.entity.FileMetadata;
import java.util.List;

public interface FileMetadataService {

    FileMetadata getMetadata(String fileId);

    void saveMetadata(FileMetadata fileMetadata);

    void updateMetadata(FileMetadata fileMetadata);

    void deleteMetadata(String fileId);

    void assertCanAccess(String fileId);

    PageResult<FileMetadata> getFileList(int page, int size, String sortBy, String direction, Long ownerUserId);

    PageResult<FileMetadata> searchFiles(String keyword, int page, int size, Long ownerUserId);

    PageResult<FileMetadata> getFileListByType(String fileType, int page, int size, String sortBy, String direction, Long ownerUserId);

    Object getDirectoryStructure(String path);

} 
