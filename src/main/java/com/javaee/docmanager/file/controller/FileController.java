package com.javaee.docmanager.file.controller;

import com.javaee.docmanager.common.model.Result;
import com.javaee.docmanager.file.entity.FileMetadata;
import com.javaee.docmanager.file.mapper.FileMetadataMapper;
import com.javaee.docmanager.file.service.FileMetadataService;
import com.javaee.docmanager.file.service.FileService;
import com.javaee.docmanager.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.javaee.docmanager.file.util.FileUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/files")
@Tag(name = "文件管理", description = "文件上传、下载、删除、分片等核心接口")
public class FileController {

    @Autowired
    private FileService fileService;

    @Autowired
    private FileMetadataService fileMetadataService;

    @Autowired
    private FileMetadataMapper fileMetadataMapper;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "单文件上传", description = "上传单个文件到服务器")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        log.info("=== 收到文件上传请求 ===");
        log.info("文件名: {}, 文件大小: {}, 文件类型: {}",
            file.getOriginalFilename(), file.getSize(), file.getContentType());
        try {
            String fileId = fileService.upload(file);
            log.info("文件上传成功，文件ID: {}", fileId);

            try {
                Map<String, Object> message = new HashMap<>();
                message.put("fileId", fileId);
                message.put("fileName", file.getOriginalFilename());
                message.put("fileSize", file.getSize());
                message.put("userId", UserContext.getCurrentUserId());
                message.put("timestamp", LocalDateTime.now().toString());

                log.info("发送文件上传消息到 Kafka");
                kafkaTemplate.send("file-upload", objectMapper.writeValueAsString(message));
            } catch (Exception e) {
                log.error("发送Kafka消息失败", e);
            }

            return Result.success(Map.of("fileId", fileId, "message", "文件上传成功"));
        } catch (Exception e) {
            log.error("文件上传失败: {}", e.getMessage(), e);
            return Result.fail("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "多文件上传", description = "上传多个文件到服务器")
    public Result<Map<String, Object>> uploadMultiple(@RequestParam("files") MultipartFile[] files) {
        System.out.println("=== 收到多文件上传请求 ===");
        System.out.println("文件数量: " + files.length);
        try {
            System.out.println("调用fileService.uploadMultiple方法");
            String[] fileIds = fileService.uploadMultiple(files);
            System.out.println("多文件上传成功，文件ID数量: " + fileIds.length);
            return Result.success(Map.of("fileIds", fileIds, "message", "文件上传成功", "count", fileIds.length));
        } catch (Exception e) {
            System.out.println("多文件上传失败: " + e.getMessage());
            e.printStackTrace();
            return Result.fail("文件上传失败: " + e.getMessage());
        }
    }

    @PostMapping(value = "/upload-chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "分片上传", description = "大文件分片上传")
    public ResponseEntity<Map<String, String>> uploadChunk(@RequestParam("chunk") MultipartFile chunk,
                           @RequestParam("fileId") String fileId,
                           @RequestParam("chunkIndex") int chunkIndex,
                           @RequestParam("totalChunks") int totalChunks) {
        try {
            fileService.uploadChunk(chunk, fileId, chunkIndex, totalChunks);
            return ResponseEntity.ok(Map.of("message", "分片上传成功", "chunkIndex", String.valueOf(chunkIndex)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "分片上传失败: " + e.getMessage()));
        }
    }

    @PostMapping("/merge-chunk")
    @Operation(summary = "分片合并", description = "合并文件分片")
    public ResponseEntity<Map<String, String>> mergeChunk(@Parameter(description = "文件唯一标识") @RequestParam("fileId") String fileId,
                          @Parameter(description = "文件名") @RequestParam("fileName") String fileName) {
        try {
            String mergedFileId = fileService.mergeChunk(fileId, fileName);
            return ResponseEntity.ok(Map.of("fileId", mergedFileId, "message", "文件合并成功"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "文件合并失败: " + e.getMessage()));
        }
    }

    @GetMapping("/download/{fileId}")
    @Operation(summary = "文件下载", description = "下载指定文件")
    public ResponseEntity<byte[]> download(@Parameter(description = "文件ID") @PathVariable String fileId,
                                           @RequestParam(value = "fileName", required = false) String fileName) {
        try {
            fileMetadataService.assertCanAccess(fileId);
            byte[] fileBytes = fileService.download(fileId);
            HttpHeaders headers = buildFileResponseHeaders(fileId, fileName, true);

            try {
                Map<String, Object> message = new HashMap<>();
                message.put("fileId", fileId);
                message.put("userId", UserContext.getCurrentUserId());
                message.put("timestamp", LocalDateTime.now().toString());
                log.info("发送文件下载消息到 Kafka");
                kafkaTemplate.send("file-download", objectMapper.writeValueAsString(message));
            } catch (Exception e) {
                log.error("发送Kafka消息失败", e);
            }

            return ResponseEntity.ok().headers(headers).body(fileBytes);
        } catch (Exception e) {
            log.error("文件下载失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("文件下载失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/preview/{fileId}")
    @Operation(summary = "文件预览", description = "预览指定文件")
    public ResponseEntity<byte[]> preview(@Parameter(description = "文件ID") @PathVariable String fileId,
                                          @RequestParam(value = "fileName", required = false) String fileName) {
        try {
            fileMetadataService.assertCanAccess(fileId);
            byte[] fileBytes = fileService.download(fileId);
            HttpHeaders headers = buildFileResponseHeaders(fileId, fileName, false);

            return ResponseEntity.ok().headers(headers).body(fileBytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("文件预览失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private HttpHeaders buildFileResponseHeaders(String fileId, String fileName, boolean attachment) {
        HttpHeaders headers = new HttpHeaders();
        FileMetadata metadata = fileMetadataMapper.selectByFileId(fileId);
        String displayName = resolveDisplayName(fileId, fileName, metadata);
        String contentType = metadata != null && metadata.getFileType() != null
                ? metadata.getFileType()
                : FileUtils.getContentType(displayName);
        headers.setContentType(MediaType.parseMediaType(contentType));
        ContentDisposition.Builder disposition = attachment
                ? ContentDisposition.attachment()
                : ContentDisposition.inline();
        headers.setContentDisposition(disposition.filename(displayName, StandardCharsets.UTF_8).build());
        return headers;
    }

    private String resolveDisplayName(String fileId, String fileName, FileMetadata metadata) {
        if (metadata != null && metadata.getFileName() != null && !metadata.getFileName().isBlank()) {
            return metadata.getFileName();
        }
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        return fileService.resolveStorageKey(fileId);
    }

    @DeleteMapping("/{fileId}")
    @Operation(summary = "文件删除", description = "删除指定文件")
    public ResponseEntity<Map<String, String>> delete(@Parameter(description = "文件ID") @PathVariable String fileId) {
        try {
            fileMetadataService.assertCanAccess(fileId);
            fileService.delete(fileId);

            try {
                Map<String, Object> message = new HashMap<>();
                message.put("fileId", fileId);
                message.put("userId", UserContext.getCurrentUserId());
                message.put("timestamp", LocalDateTime.now().toString());
                log.info("发送文件删除消息到 Kafka");
                kafkaTemplate.send("file-delete", objectMapper.writeValueAsString(message));
            } catch (Exception e) {
                log.error("发送Kafka消息失败", e);
            }

            return ResponseEntity.ok(Map.of("message", "文件删除成功"));
        } catch (Exception e) {
            log.error("文件删除失败: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "文件删除失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{fileId}/rename")
    @Operation(summary = "文件重命名", description = "重命名指定文件")
    public ResponseEntity<Map<String, String>> rename(@Parameter(description = "文件ID") @PathVariable String fileId,
                      @Parameter(description = "新文件名") @RequestParam("newName") String newName) {
        try {
            fileService.rename(fileId, newName);
            return ResponseEntity.ok(Map.of("message", "文件重命名成功"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "文件重命名失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{fileId}/move")
    @Operation(summary = "文件移动", description = "移动文件到指定目录")
    public ResponseEntity<Map<String, String>> move(@Parameter(description = "文件ID") @PathVariable String fileId,
                    @Parameter(description = "目标目录") @RequestParam("targetPath") String targetPath) {
        try {
            fileService.move(fileId, targetPath);
            return ResponseEntity.ok(Map.of("message", "文件移动成功"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "文件移动失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{fileId}/copy")
    @Operation(summary = "文件复制", description = "复制文件到指定目录")
    public ResponseEntity<Map<String, String>> copy(@Parameter(description = "文件ID") @PathVariable String fileId,
                    @Parameter(description = "目标目录") @RequestParam("targetPath") String targetPath) {
        try {
            String newFileId = fileService.copy(fileId, targetPath);
            return ResponseEntity.ok(Map.of("fileId", newFileId, "message", "文件复制成功"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "文件复制失败: " + e.getMessage()));
        }
    }
}
