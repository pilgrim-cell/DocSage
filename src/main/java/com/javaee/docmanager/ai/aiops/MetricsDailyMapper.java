package com.javaee.docmanager.ai.aiops;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MetricsDailyMapper {

    List<MetricsDaily> selectByUserId(@Param("userId") Long userId);

    List<MetricsDaily> selectByUserIdAndDateRange(@Param("userId") Long userId,
                                                  @Param("startDate") String startDate,
                                                  @Param("endDate") String endDate);

    int insertOrUpdate(MetricsDaily record);
}
