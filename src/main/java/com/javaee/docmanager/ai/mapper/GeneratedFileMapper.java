package com.javaee.docmanager.ai.mapper;

import com.javaee.docmanager.ai.entity.GeneratedFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GeneratedFileMapper {
    int insert(GeneratedFile file);
    GeneratedFile selectById(@Param("id") String id);
    List<GeneratedFile> selectRecent(@Param("limit") int limit, @Param("createBy") String createBy);
    int deleteById(@Param("id") String id);
    int updateTitle(@Param("id") String id, @Param("title") String title);
    int updateCurrentVersion(@Param("id") String id, @Param("currentVersionId") String currentVersionId,
                             @Param("title") String title, @Param("objectKey") String objectKey);
}
