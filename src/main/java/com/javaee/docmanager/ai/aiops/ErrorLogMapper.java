package com.javaee.docmanager.ai.aiops;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ErrorLogMapper {

    List<ErrorLog> selectLatest(@Param("limit") int limit);

    ErrorLog selectBySourceAndMessage(@Param("source") String source, @Param("errorMessage") String errorMessage);

    int insert(ErrorLog record);

    int incrementCount(@Param("id") Long id, @Param("aiAnalysis") String aiAnalysis);
}
