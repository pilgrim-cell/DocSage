package com.javaee.docmanager.ai.mapper;

import com.javaee.docmanager.ai.entity.GeneratedFileVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GeneratedFileVersionMapper {
    int insert(GeneratedFileVersion version);

    GeneratedFileVersion selectById(@Param("id") String id);

    List<GeneratedFileVersion> selectByFileId(@Param("fileId") String fileId);

    GeneratedFileVersion selectDraftByFileId(@Param("fileId") String fileId);

    int selectMaxVersionNumber(@Param("fileId") String fileId);

    int countByFileId(@Param("fileId") String fileId);

    int deleteDraftsByFileId(@Param("fileId") String fileId);

    int deleteByFileId(@Param("fileId") String fileId);

    GeneratedFileVersion selectOldestByFileId(@Param("fileId") String fileId);

    int deleteById(@Param("id") String id);

    int updateStatus(@Param("id") String id, @Param("status") String status);
}
