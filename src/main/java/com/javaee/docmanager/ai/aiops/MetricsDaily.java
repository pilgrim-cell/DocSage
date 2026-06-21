package com.javaee.docmanager.ai.aiops;

import lombok.Data;

import java.time.LocalDate;

@Data
public class MetricsDaily {
    private Long id;
    private Long userId;
    private LocalDate date;
    private Long ragTokensInput;
    private Long ragTokensOutput;
    private Long pptTokensInput;
    private Long pptTokensOutput;
    private Integer ragDocCount;
    private Integer ragSliceCount;
    private Integer pptCount;
}
