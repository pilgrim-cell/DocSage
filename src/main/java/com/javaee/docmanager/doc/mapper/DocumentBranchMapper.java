package com.javaee.docmanager.doc.mapper;

import com.javaee.docmanager.doc.entity.DocumentFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentBranchMapper {

    List<DocumentFile> selectByDocumentId(@Param("documentId") String documentId);

    DocumentFile selectByDocumentIdAndBranch(@Param("documentId") String documentId,
                                               @Param("branchName") String branchName);

    int deleteBranchesByDocumentId(@Param("documentId") String documentId);

    int deleteByDocumentIdAndBranch(@Param("documentId") String documentId,
                                    @Param("branchName") String branchName);
}
