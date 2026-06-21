package com.javaee.docmanager.ai.entity;

import lombok.Data;

import java.util.Date;

@Data
public class GeneratedFileVersion {
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_DRAFT = "draft";

    private String id;
    private String fileId;
    private int versionNumber;
    private String title;
    private String objectKey;
    private Integer sectionCount;
    private String changeLog;
    /** applied | draft */
    private String status;
    private String createBy;
    private Date createTime;
}
