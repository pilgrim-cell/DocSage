package com.javaee.docmanager.user.controller;

import com.javaee.docmanager.common.model.Result;
import com.javaee.docmanager.limiter.RateLimit;
import com.javaee.docmanager.ai.aiops.MonitoringService;
import com.javaee.docmanager.security.ResourceAccessService;
import com.javaee.docmanager.security.UserContext;
import com.javaee.docmanager.user.dto.AdminResetPasswordDTO;
import com.javaee.docmanager.user.dto.ChangePasswordDTO;
import com.javaee.docmanager.user.dto.LoginDTO;
import com.javaee.docmanager.user.dto.RegisterDTO;
import com.javaee.docmanager.user.service.UserService;
import com.javaee.docmanager.user.vo.LoginVO;
import com.javaee.docmanager.user.vo.UserVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理", description = "用户登录、注册、信息管理等接口")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private ResourceAccessService resourceAccessService;

    @Autowired
    private MonitoringService monitoringService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping("/login")
    @RateLimit(timeWindow = 60, maxRequests = 10)
    @Operation(summary = "用户登录", description = "根据用户名和密码进行登录，返回访问令牌和刷新令牌")
    public Result<LoginVO> login(@RequestBody LoginDTO loginDTO) {
        log.info("用户登录: {}", loginDTO.getUsername());
        LoginVO loginVO = userService.login(loginDTO);

        Long userId = loginVO.getUser().getId();
        String username = loginVO.getUser().getUsername();
        java.util.concurrent.CompletableFuture.runAsync(() ->
                sendUserOperateLog(userId, "LOGIN", "用户 " + username + " 登录成功"));

        return Result.success(loginVO);
    }

    @PostMapping("/register")
    @RateLimit(timeWindow = 60, maxRequests = 5)
    @Operation(summary = "用户注册", description = "创建新用户，返回用户信息")
    public Result<UserVO> register(@RequestBody RegisterDTO registerDTO) {
        log.info("用户注册: {}", registerDTO.getUsername());
        UserVO userVO = userService.register(registerDTO);

        try {
            Map<String, Object> registerMessage = new HashMap<>();
            registerMessage.put("userId", userVO.getId());
            registerMessage.put("username", userVO.getUsername());
            registerMessage.put("email", userVO.getEmail());
            registerMessage.put("timestamp", LocalDateTime.now().toString());

            log.info("发送用户注册消息到 Kafka");
            kafkaTemplate.send("user-register", objectMapper.writeValueAsString(registerMessage));
        } catch (Exception e) {
            log.error("发送注册消息失败", e);
        }

        Long userId = userVO.getId();
        String username = userVO.getUsername();
        java.util.concurrent.CompletableFuture.runAsync(() ->
                sendUserOperateLog(userId, "REGISTER", "用户 " + username + " 注册成功"));

        return Result.success(userVO);
    }

    private void sendUserOperateLog(Long userId, String operation, String description) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("userId", userId);
            message.put("operation", operation);
            message.put("description", description);
            message.put("timestamp", LocalDateTime.now().toString());

            log.info("发送用户操作日志消息到 Kafka");
            kafkaTemplate.send("user-operate-log", objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("发送操作日志消息失败（不影响主流程）: {}", e.getMessage());
        }
    }

    @GetMapping("/me/ai-chat-count")
    @Operation(summary = "AI对话次数", description = "知识库问答 + PPT 对话累计次数（仪表盘用）")
    public Result<Map<String, Long>> getAiChatCount() {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        Map<String, Long> data = new HashMap<>();
        long rag = monitoringService.getCounterForUser(userId, "rag.queries");
        long ppt = monitoringService.getCounterForUser(userId, "ppt.chats");
        data.put("ragQueryCount", rag);
        data.put("pptChatCount", ppt);
        data.put("aiChatCount", rag + ppt);
        return Result.success(data);
    }

    @GetMapping("/list")
    @Operation(summary = "用户列表", description = "管理员获取全部启用用户（用于筛选）")
    public Result<List<UserVO>> listUsers() {
        try {
            resourceAccessService.assertAdmin();
            return Result.success(userService.listActiveUsers());
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/manage")
    @Operation(summary = "用户管理列表", description = "管理员查看全部用户")
    public Result<List<UserVO>> listUsersForManage() {
        try {
            resourceAccessService.assertAdmin();
            return Result.success(userService.listAllForManage());
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/me/password")
    @Operation(summary = "修改自己的密码")
    public Result<Void> changeMyPassword(@RequestBody ChangePasswordDTO dto) {
        try {
            Long userId = UserContext.getCurrentUserId();
            if (userId == null) {
                return Result.fail("请先登录");
            }
            userService.changePassword(userId, dto.getOldPassword(), dto.getNewPassword());
            return Result.success();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}/password")
    @Operation(summary = "管理员重置用户密码")
    public Result<Void> adminResetPassword(
            @PathVariable Long id,
            @RequestBody AdminResetPasswordDTO dto) {
        try {
            resourceAccessService.assertAdmin();
            userService.adminResetPassword(id, dto.getNewPassword());
            return Result.success();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "管理员修改用户状态", description = "1启用 0禁用")
    public Result<Void> adminUpdateStatus(
            @PathVariable Long id,
            @RequestParam Integer status) {
        try {
            resourceAccessService.assertAdmin();
            if (id.equals(UserContext.getCurrentUserId())) {
                return Result.fail("不能修改自己的账号状态");
            }
            userService.adminUpdateStatus(id, status);
            return Result.success();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户信息", description = "根据用户ID获取用户详细信息")
    public Result<UserVO> getUserById(@Parameter(description = "用户ID") @PathVariable Long id) {
        log.info("获取用户信息: {}", id);
        UserVO userVO = userService.getUserById(id);
        return Result.success(userVO);
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "使用刷新令牌获取新的访问令牌")
    public Result<String> refreshToken(@Parameter(description = "刷新令牌") @RequestParam String refreshToken) {
        log.info("刷新令牌");
        String accessToken = userService.refreshToken(refreshToken);
        return Result.success(accessToken);
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "用户登出操作")
    public Result<Void> logout() {
        log.info("用户登出");
        return Result.success();
    }
}
