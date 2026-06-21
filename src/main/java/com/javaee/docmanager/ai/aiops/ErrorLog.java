package com.javaee.docmanager.ai.aiops;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ErrorLog {
    private Long id;
    private String source;
    private String errorMessage;
    private Integer errorCount;
    private String aiAnalysis;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
