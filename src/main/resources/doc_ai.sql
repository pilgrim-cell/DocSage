/*
 Navicat Premium Dump SQL

 Source Server         : localhost_3307
 Source Server Type    : MySQL
 Source Server Version : 80046 (8.0.46)
 Source Host           : localhost:3307
 Source Schema         : doc_ai

 Target Server Type    : MySQL
 Target Server Version : 80046 (8.0.46)
 File Encoding         : 65001

 Date: 20/06/2026 20:45:50
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for document
-- ----------------------------
DROP TABLE IF EXISTS `document`;
CREATE TABLE `document`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'æ–‡æ¡£ID',
  `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ ‡é¢˜',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'æ­£æ–‡å†…å®¹',
  `summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'æ‘˜è¦',
  `keywords` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å…³é”®è¯',
  `file_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å…³è”æ–‡ä»¶ID',
  `user_id` bigint NULL DEFAULT NULL COMMENT 'æ‰€å±žç”¨æˆ·',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'active' COMMENT 'çŠ¶æ€',
  `version` int NULL DEFAULT 1 COMMENT 'ç‰ˆæœ¬å·',
  `category` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'åˆ†ç±»',
  `tags` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ ‡ç­¾',
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'åˆ›å»ºäºº',
  `create_time` datetime NULL DEFAULT NULL COMMENT 'åˆ›å»ºæ—¶é—´',
  `update_time` datetime NULL DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'ç»“æž„åŒ–æ–‡æ¡£è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for document_file
-- ----------------------------
DROP TABLE IF EXISTS `document_file`;
CREATE TABLE `document_file`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'åˆ†æ”¯è®°å½•ID',
  `document_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'æ–‡æ¡£ç»„IDï¼ˆå¤šåˆ†æ”¯å…±äº«ï¼‰',
  `branch_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'main' COMMENT 'åˆ†æ”¯å',
  `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ–‡æ¡£æ ‡é¢˜',
  `current_file_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å½“å‰ç‰ˆæœ¬æ–‡ä»¶ID',
  `current_version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å½“å‰ç‰ˆæœ¬å·',
  `file_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ–‡ä»¶MIMEç±»åž‹',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'active' COMMENT 'çŠ¶æ€',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'åˆ›å»ºäºº',
  `user_id` bigint NULL DEFAULT NULL COMMENT '所属用户ID',
  `create_time` datetime NULL DEFAULT NULL COMMENT 'åˆ›å»ºæ—¶é—´',
  `update_time` datetime NULL DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_document_id`(`document_id` ASC) USING BTREE,
  INDEX `idx_branch`(`document_id` ASC, `branch_name` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'æ–‡æ¡£æ–‡ä»¶è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for document_file_version
-- ----------------------------
DROP TABLE IF EXISTS `document_file_version`;
CREATE TABLE `document_file_version`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'ç‰ˆæœ¬è®°å½•ID',
  `document_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'æ–‡æ¡£ç»„ID',
  `branch_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'åˆ†æ”¯è®°å½•ID',
  `file_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'æ–‡ä»¶ID',
  `version` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'ç‰ˆæœ¬å·',
  `change_log` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å˜æ›´è¯´æ˜Ž',
  `uploaded_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'ä¸Šä¼ äºº',
  `upload_time` datetime NULL DEFAULT NULL COMMENT 'ä¸Šä¼ æ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_document_id`(`document_id` ASC) USING BTREE,
  INDEX `idx_branch_id`(`branch_id` ASC) USING BTREE,
  INDEX `idx_upload_time`(`upload_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'æ–‡æ¡£æ–‡ä»¶ç‰ˆæœ¬è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for document_version
-- ----------------------------
DROP TABLE IF EXISTS `document_version`;
CREATE TABLE `document_version`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'ç‰ˆæœ¬ID',
  `document_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'æ–‡æ¡£ID',
  `version_number` int NULL DEFAULT NULL COMMENT 'ç‰ˆæœ¬åºå·',
  `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ ‡é¢˜',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'å†…å®¹',
  `summary` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'æ‘˜è¦',
  `keywords` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å…³é”®è¯',
  `change_log` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å˜æ›´è¯´æ˜Ž',
  `created_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'åˆ›å»ºäºº',
  `create_time` datetime NULL DEFAULT NULL COMMENT 'åˆ›å»ºæ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_document_id`(`document_id` ASC) USING BTREE,
  INDEX `idx_version`(`document_id` ASC, `version_number` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'ç»“æž„åŒ–æ–‡æ¡£ç‰ˆæœ¬è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for error_log
-- ----------------------------
DROP TABLE IF EXISTS `error_log`;
CREATE TABLE `error_log`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®',
  `source` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'é”™è¯¯æ¥æº',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'é”™è¯¯ä¿¡æ¯',
  `error_count` int NULL DEFAULT 1 COMMENT 'å‡ºçŽ°æ¬¡æ•°',
  `ai_analysis` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'AIåˆ†æžç»“æžœ',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'é¦–æ¬¡å‡ºçŽ°æ—¶é—´',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'æœ€åŽå‡ºçŽ°æ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE,
  INDEX `idx_source_message`(`source`(100) ASC, `error_message`(200) ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'é”™è¯¯æ—¥å¿—è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for file_metadata
-- ----------------------------
DROP TABLE IF EXISTS `file_metadata`;
CREATE TABLE `file_metadata`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'è®°å½•ID',
  `file_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'æ–‡ä»¶ID',
  `file_name` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'å­˜å‚¨æ–‡ä»¶å',
  `original_file_name` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'åŽŸå§‹æ–‡ä»¶å',
  `file_path` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ–‡ä»¶è·¯å¾„',
  `file_type` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'MIMEç±»åž‹',
  `file_size` bigint NULL DEFAULT 0 COMMENT 'æ–‡ä»¶å¤§å°ï¼ˆå­—èŠ‚ï¼‰',
  `md5` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'MD5',
  `storage_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'minio' COMMENT 'å­˜å‚¨ç±»åž‹',
  `bucket_name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'MinIOæ¡¶å',
  `object_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'MinIOå¯¹è±¡é”®',
  `status` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'active' COMMENT 'çŠ¶æ€',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'åˆ›å»ºäºº',
  `create_time` datetime NULL DEFAULT NULL COMMENT 'åˆ›å»ºæ—¶é—´',
  `update_time` datetime NULL DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_file_id`(`file_id` ASC) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE,
  INDEX `idx_file_type`(`file_type` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'æ–‡ä»¶å…ƒæ•°æ®è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for generated_file
-- ----------------------------
DROP TABLE IF EXISTS `generated_file`;
CREATE TABLE `generated_file`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'è®°å½•ID',
  `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ ‡é¢˜',
  `file_format` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ ¼å¼ï¼šppt/htmlç­‰',
  `file_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ–‡ä»¶ID',
  `object_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'MinIOå¯¹è±¡é”®',
  `current_version_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '当前生效版本ID',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'åˆ›å»ºäºº',
  `create_time` datetime NULL DEFAULT NULL COMMENT 'åˆ›å»ºæ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AIç”Ÿæˆæ–‡ä»¶è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for generated_file_version
-- ----------------------------
DROP TABLE IF EXISTS `generated_file_version`;
CREATE TABLE `generated_file_version`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '版本ID',
  `file_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '所属生成文件ID',
  `version_number` int NOT NULL COMMENT '版本号',
  `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '该版本标题',
  `object_key` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'MinIO对象键',
  `section_count` int NULL DEFAULT NULL COMMENT '页数',
  `change_log` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '变更说明',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'applied' COMMENT 'applied|draft',
  `create_by` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '创建人',
  `create_time` datetime NULL DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_file_version`(`file_id` ASC, `version_number` ASC) USING BTREE,
  INDEX `idx_file_id`(`file_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI生成文件版本表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for metrics_daily
-- ----------------------------
DROP TABLE IF EXISTS `metrics_daily`;
CREATE TABLE `metrics_daily`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®',
  `user_id` bigint NOT NULL COMMENT 'ç”¨æˆ·ID',
  `date` date NOT NULL COMMENT 'ç»Ÿè®¡æ—¥æœŸ',
  `rag_tokens_input` bigint NULL DEFAULT 0 COMMENT 'RAGè¾“å…¥Token',
  `rag_tokens_output` bigint NULL DEFAULT 0 COMMENT 'RAGè¾“å‡ºToken',
  `ppt_tokens_input` bigint NULL DEFAULT 0 COMMENT 'PPTè¾“å…¥Token',
  `ppt_tokens_output` bigint NULL DEFAULT 0 COMMENT 'PPTè¾“å‡ºToken',
  `rag_doc_count` int NULL DEFAULT 0 COMMENT 'RAGæ–‡æ¡£æ•°',
  `rag_slice_count` int NULL DEFAULT 0 COMMENT 'RAGåˆ‡ç‰‡æ•°',
  `ppt_count` int NULL DEFAULT 0 COMMENT 'PPTç”Ÿæˆæ•°',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_date`(`user_id` ASC, `date` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_date`(`date` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 48 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'æ¯æ—¥æŒ‡æ ‡è¡¨' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ç”¨æˆ·ID',
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'ç”¨æˆ·å',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'å¯†ç ï¼ˆBCryptåŠ å¯†ï¼‰',
  `email` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'é‚®ç®±',
  `phone` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'æ‰‹æœºå·',
  `role` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT 'USER' COMMENT 'è§’è‰²',
  `status` int NULL DEFAULT 1 COMMENT 'çŠ¶æ€ï¼š1æ­£å¸¸ 0ç¦ç”¨',
  `rag_tokens_input` bigint NULL DEFAULT 0 COMMENT 'RAGè¾“å…¥Tokenç´¯è®¡',
  `rag_tokens_output` bigint NULL DEFAULT 0 COMMENT 'RAGè¾“å‡ºTokenç´¯è®¡',
  `ppt_tokens_input` bigint NULL DEFAULT 0 COMMENT 'PPTè¾“å…¥Tokenç´¯è®¡',
  `ppt_tokens_output` bigint NULL DEFAULT 0 COMMENT 'PPTè¾“å‡ºTokenç´¯è®¡',
  `rag_doc_count` bigint NULL DEFAULT 0 COMMENT 'RAGç´¢å¼•æ–‡æ¡£æ•°',
  `rag_slice_count` bigint NULL DEFAULT 0 COMMENT 'RAGåˆ‡ç‰‡æ•°',
  `ppt_count` bigint NULL DEFAULT 0 COMMENT 'PPTç”Ÿæˆæ•°',
  `create_time` datetime NULL DEFAULT NULL COMMENT 'åˆ›å»ºæ—¶é—´',
  `update_time` datetime NULL DEFAULT NULL COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE,
  INDEX `idx_email`(`email` ASC) USING BTREE,
  INDEX `idx_phone`(`phone` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 7 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'ç”¨æˆ·è¡¨' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
