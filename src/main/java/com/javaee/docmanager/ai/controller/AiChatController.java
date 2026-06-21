package com.javaee.docmanager.ai.controller;

import com.javaee.docmanager.ai.entity.GeneratedFile;
import com.javaee.docmanager.ai.entity.GeneratedFileVersion;
import com.javaee.docmanager.ai.service.AiChatService;
import com.javaee.docmanager.ai.service.AiChatService.PptChatOutcome;
import com.javaee.docmanager.ai.service.ppt.PptReferenceService;
import com.javaee.docmanager.common.model.Result;
import com.javaee.docmanager.security.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/ai/chat")
@Tag(name = "AI对话", description = "AI对话与文件生成")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatService aiChatService;
    private final PptReferenceService pptReferenceService;

    @PostMapping
    @Operation(summary = "AI对话", description = "与大模型对话，返回回复")
    public Result<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        String reply = aiChatService.chat(message);
        return Result.success(Map.of("reply", reply));
    }

    @PostMapping("/generate-ppt")
    @Operation(summary = "生成PPT", description = "使用PPT Skill生成横向翻页HTML PPT")
    public Result<Map<String, Object>> generatePpt(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        GeneratedFile gf = aiChatService.generatePpt(message);
        Map<String, Object> data = Map.of(
                "fileId", gf.getId(),
                "fileName", gf.getTitle(),
                "format", gf.getFileFormat()
        );
        return Result.success(data);
    }

    @PostMapping("/ppt")
    @Operation(summary = "多轮对话生成PPT", description = "AI先与用户交流确认需求，最多5轮后自动生成")
    public Result<Map<String, Object>> chatForPpt(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String conversationId = body.get("conversationId");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = java.util.UUID.randomUUID().toString();
        }

        PptChatOutcome outcome = aiChatService.chatForPpt(conversationId, message);
        GeneratedFile gf = outcome.getFile();

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (gf != null) {
            boolean refining = aiChatService.isPptRefinable(conversationId);
            data.put("type", "file");
            data.put("fileId", gf.getId());
            data.put("fileName", gf.getTitle());
            data.put("format", gf.getFileFormat());
            data.put("conversationId", conversationId);
            data.put("refinable", refining);
            data.put("isRefinement", outcome.isRefinement());
            data.put("currentVersionId", gf.getCurrentVersionId());
            appendDraftVersionInfo(data, outcome.getDraftVersion());
            if (refining) {
                if (outcome.getDraftVersion() != null) {
                    data.put("hint", "已生成优化草稿 v" + outcome.getDraftVersion().getVersionNumber()
                            + "，请预览后手动「应用此版本」；不满意可回退或丢弃草稿后继续修改。");
                } else {
                    data.put("hint", "如需修改请描述具体内容；优化结果将保存为草稿，需手动应用后才会覆盖当前版本。");
                }
            }
        } else {
            // AI 在提问或优化中需要澄清
            data.put("type", "question");
            data.put("question", aiChatService.getLastPptQuestion(conversationId));
            data.put("conversationId", conversationId);
            data.put("refinable", aiChatService.isPptRefinable(conversationId));
        }
        return Result.success(data);
    }

    @PostMapping("/ppt/finish")
    @Operation(summary = "结束PPT会话", description = "结束当前PPT制作/优化，开始下一份")
    public Result<Void> finishPptSession(@RequestBody(required = false) Map<String, String> body) {
        String conversationId = body != null ? body.get("conversationId") : null;
        if (conversationId != null && !conversationId.isBlank()) {
            aiChatService.finishPptSession(conversationId);
        }
        return Result.success();
    }

    @PostMapping(value = "/ppt/refs/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "PPT附加参考文档(本地上传)", description = "自动判定主/次文档；超长自动降为次文档")
    public Result<Map<String, Object>> uploadPptReference(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "preferredRole", defaultValue = "auto") String preferredRole) {
        try {
            String username = UserContext.getCurrentUsername();
            conversationId = pptReferenceService.ensureConversationId(conversationId);
            Map<String, Object> result = pptReferenceService.attachFromUpload(
                    conversationId, file, preferredRole, username);
            return Result.success(result);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/ppt/refs/from-library/{sourceDocumentId}")
    @Operation(summary = "PPT附加参考文档(文档库)", description = "从文档库选取文档作为PPT参考")
    public Result<Map<String, Object>> attachPptReferenceFromLibrary(
            @PathVariable String sourceDocumentId,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String conversationId = body != null ? body.get("conversationId") : null;
            String preferredRole = body != null && body.get("preferredRole") != null
                    ? body.get("preferredRole") : "auto";
            conversationId = pptReferenceService.ensureConversationId(conversationId);
            Map<String, Object> result = pptReferenceService.attachFromLibrary(
                    conversationId, sourceDocumentId, preferredRole,
                    UserContext.getCurrentUsername(), UserContext.getCurrentRole());
            return Result.success(result);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/ppt/refs")
    @Operation(summary = "列出PPT会话参考文档")
    public Result<Map<String, Object>> listPptReferences(
            @RequestParam String conversationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("refs", pptReferenceService.listRefSummaries(conversationId));
        return Result.success(data);
    }

    @DeleteMapping("/ppt/refs/{refId}")
    @Operation(summary = "移除PPT参考文档")
    public Result<Void> removePptReference(
            @PathVariable String refId,
            @RequestParam String conversationId) {
        pptReferenceService.removeRef(conversationId, refId);
        return Result.success();
    }

    @PostMapping("/generate")
    @Operation(summary = "生成文件", description = "让大模型生成内容并保存为Word/PDF/PPT文件")
    public Result<Map<String, Object>> generateFile(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        String format = body.get("format");
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        if (format == null || format.isBlank()) {
            return Result.fail("文件格式不能为空");
        }
        GeneratedFile gf = aiChatService.generateFile(message, format);
        Map<String, Object> data = Map.of(
                "fileId", gf.getId(),
                "fileName", gf.getTitle(),
                "format", gf.getFileFormat()
        );
        return Result.success(data);
    }

    @GetMapping("/files/{id}/versions")
    @Operation(summary = "PPT版本列表", description = "查看生成文件的所有版本（含草稿）")
    public Result<List<GeneratedFileVersion>> listFileVersions(@PathVariable String id) {
        return Result.success(aiChatService.listPptVersions(id));
    }

    @PostMapping("/files/{id}/versions/{versionId}/apply")
    @Operation(summary = "应用PPT版本", description = "将指定版本设为当前生效版本（手动覆盖）")
    public Result<Map<String, Object>> applyFileVersion(
            @PathVariable String id,
            @PathVariable String versionId,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String conversationId = body != null ? body.get("conversationId") : null;
            GeneratedFile gf = aiChatService.applyPptVersion(conversationId, id, versionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fileId", gf.getId());
            data.put("fileName", gf.getTitle());
            data.put("currentVersionId", gf.getCurrentVersionId());
            data.put("message", "已应用所选版本为当前版本");
            return Result.success(data);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/files/{id}/versions/{versionId}/rollback")
    @Operation(summary = "回退PPT版本", description = "回退到历史版本并设为当前生效版本")
    public Result<Map<String, Object>> rollbackFileVersion(
            @PathVariable String id,
            @PathVariable String versionId,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String conversationId = body != null ? body.get("conversationId") : null;
            GeneratedFile gf = aiChatService.rollbackPptVersion(conversationId, id, versionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fileId", gf.getId());
            data.put("fileName", gf.getTitle());
            data.put("currentVersionId", gf.getCurrentVersionId());
            data.put("message", "已回退到所选版本");
            return Result.success(data);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @DeleteMapping("/files/{id}/versions/draft")
    @Operation(summary = "丢弃PPT优化草稿")
    public Result<Void> discardFileDraft(
            @PathVariable String id,
            @RequestParam(required = false) String conversationId) {
        aiChatService.discardPptDraft(conversationId, id);
        return Result.success();
    }

    @PostMapping("/files/{id}/save-to-library")
    @Operation(summary = "存入文档库", description = "将 AI 生成的 HTML PPT 保存到文档管理列表")
    public Result<Map<String, Object>> saveGeneratedFileToLibrary(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String versionId = body != null ? body.get("versionId") : null;
            var doc = aiChatService.saveGeneratedFileToLibrary(id, versionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", doc.getDocumentId());
            data.put("title", doc.getTitle());
            data.put("fileType", doc.getFileType());
            data.put("currentVersion", doc.getCurrentVersion());
            data.put("message", "已存入文档库：" + doc.getTitle());
            return Result.success(data);
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/files")
    @Operation(summary = "最近生成文件", description = "获取最近10条生成的文件")
    public Result<List<GeneratedFile>> getRecentFiles() {
        return Result.success(aiChatService.getRecentFiles(10));
    }

    @GetMapping("/files/{id}/download")
    @Operation(summary = "下载生成文件")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String id) {
        GeneratedFile gf = aiChatService.getFile(id);
        if (gf == null) return ResponseEntity.notFound().build();
        byte[] data = aiChatService.downloadFile(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(getMediaType(gf.getFileFormat()));
        String encodedName = URLEncoder.encode(gf.getTitle(), StandardCharsets.UTF_8);
        headers.set("Content-Disposition", "attachment; filename*=UTF-8''" + encodedName);
        return ResponseEntity.ok().headers(headers).body(data);
    }

    @GetMapping("/files/{id}/preview")
    @Operation(summary = "预览生成文件", description = "支持PDF和HTML PPT在线预览；可选 versionId 预览指定版本")
    public ResponseEntity<byte[]> previewFile(
            @PathVariable String id,
            @RequestParam(required = false) String versionId) {
        GeneratedFile gf = aiChatService.getFile(id);
        if (gf == null) return ResponseEntity.notFound().build();
        byte[] data = aiChatService.previewFileVersion(id, versionId);
        HttpHeaders headers = new HttpHeaders();
        if ("ppt".equals(gf.getFileFormat())) {
            headers.setContentType(MediaType.TEXT_HTML);
        } else if ("pdf".equals(gf.getFileFormat())) {
            headers.setContentType(MediaType.APPLICATION_PDF);
        } else {
            return ResponseEntity.badRequest().build();
        }
        String encodedName = URLEncoder.encode(gf.getTitle(), StandardCharsets.UTF_8);
        headers.set("Content-Disposition", "inline; filename*=UTF-8''" + encodedName);
        return ResponseEntity.ok().headers(headers).body(data);
    }

    @DeleteMapping("/files/{id}")
    @Operation(summary = "删除生成文件")
    public Result<Void> deleteFile(@PathVariable String id) {
        aiChatService.deleteFile(id);
        return Result.success();
    }

    private MediaType getMediaType(String format) {
        return switch (format.toLowerCase()) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "word" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            case "ppt" -> MediaType.TEXT_HTML;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private void appendDraftVersionInfo(Map<String, Object> data, GeneratedFileVersion draft) {
        if (draft == null) {
            data.put("hasDraft", false);
            return;
        }
        data.put("hasDraft", true);
        data.put("draftVersionId", draft.getId());
        data.put("draftVersionNumber", draft.getVersionNumber());
        data.put("isDraft", GeneratedFileVersion.STATUS_DRAFT.equals(draft.getStatus()));
    }
}
