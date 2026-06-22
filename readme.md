# DocSage — 智能文档管理平台

DocSage（项目代号 docAI）是一个基于 Spring Boot 的**企业级 AI 文档处理与协作平台**。它将传统文档管理（上传、版本控制、分支协作）与 AI 能力（摘要、关键词提取、RAG 问答、PPT 生成、智能 Agent）整合在同一单体应用中，并内置 Vue 前端页面，开箱即可使用。

---

## 功能概览

| 模块 | 说明 |
|------|------|
| **用户系统** | 注册、登录、JWT 鉴权、角色权限、Token 刷新 |
| **文件管理** | 单/多文件上传、分片上传、下载、预览；支持 MinIO 对象存储 |
| **文档管理** | PDF / Word / PPT / CSV 文档库；版本历史、分支创建与合并 |
| **AI 基础能力** | 文本摘要、关键词提取、文档分析 |
| **AI 对话** | 多轮聊天、AI 文档/PPT 生成、生成文件版本管理与回滚 |
| **RAG 知识库** | 文档向量化索引、混合检索、重排序、流式问答 |
| **智能 Agent** | 会话管理、知识索引、计划执行 |
| **Skill 技能** | 可扩展的文件上传/下载等 Agent 技能 |
| **AIOps 运维** | AI 调用监控、异常检测、指标归档与告警 |
| **Web 界面** | 内置 `index.html` 单页应用，访问根路径即可使用 |

---

## 技术栈

- **后端**：Spring Boot 3.2 · Java 17 · MyBatis · Spring Security
- **存储**：MySQL 8 · Redis · MinIO · Elasticsearch
- **消息**：Kafka（用户/文件操作异步事件，未启动时可降级）
- **AI**：Spring AI · 阿里云百炼 DashScope · Anthropic Claude（经 AiHubMix 等兼容网关）
- **向量检索**：Qdrant · Redis Vector Store · DashScope Embedding
- **文档解析**：Apache Tika · POI · PDFBox
- **API 文档**：SpringDoc OpenAPI（Swagger UI）

---

## 环境要求

在本地运行前，请准备以下环境：

| 依赖 | 版本建议 | 用途 |
|------|----------|------|
| JDK | 17+ | 编译与运行 |
| Maven | 3.8+ | 构建项目 |
| MySQL | 8.0+ | 业务数据 |
| Redis | 6+ | 缓存、限流、向量存储、分布式锁 |
| MinIO | 最新稳定版 | 文件/文档对象存储 |
| Elasticsearch | 8.x | 全文检索（可选，部分功能依赖） |
| Kafka | 3.x | 异步事件（可选，未启动时登录等接口仍可工作） |
| Qdrant | 1.x | RAG 向量数据库 |

此外还需要申请 AI 服务密钥：

- **阿里云百炼 DashScope**：Embedding、Rerank、通义对话等
- **Anthropic Claude**（或通过 AiHubMix 等兼容 API）：AI 聊天、PPT 生成、RAG 问答

---

## 快速开始

### 1. 克隆项目

```bash
git clone <your-repo-url>
cd DocManager
```

### 2. 初始化数据库

在 MySQL 中创建数据库并导入表结构：

```sql
CREATE DATABASE doc_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

```bash
mysql -u root -p doc_ai < src/main/resources/doc_ai.sql
```

### 3. 启动中间件

以 Docker 为例（可按实际环境调整端口与密码）：

```bash
# Redis
docker run -d --name redis -p 6379:6379 redis:7

# MinIO（控制台默认 9001）
docker run -d --name minio \
  -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"

# Qdrant
docker run -d --name qdrant -p 6333:6333 qdrant/qdrant

# 一键启动
docker-compose up -d
```

启动 MinIO 后，在控制台创建与配置文件中 `minio.bucket-name` 一致的存储桶（如 `doc-ai`）。

### 4. 创建配置文件

项目的 `application.yml` **不会提交到 Git**（已在 `.gitignore` 中忽略），需要手动创建：

**路径**：`src/main/resources/application.yml`

可参考以下模板（请将占位符替换为你的实际值）：

```yaml
server:
  port: 8080

spring:
  autoconfigure:
    exclude: org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration
  application:
    name: docmanager

  datasource:
    url: jdbc:mysql://localhost:3306/doc_ai?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: your_mysql_password # 数据库密码
    driver-class-name: com.mysql.cj.jdbc.Driver

  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0
      timeout: 10000

  elasticsearch:
    uris: http://localhost:9200

  kafka:
    bootstrap-servers: localhost:9092
    producer:
      properties:
        max.block.ms: 2000
        delivery.timeout.ms: 5000
        request.timeout.ms: 2000

  servlet:
    multipart:
      enabled: true
      max-file-size: 1024MB
      max-request-size: 1024MB

  main:
    allow-bean-definition-overriding: true

  ai:
    dashscope:
      api-key: your-dashscope-api-key # 可以申请阿里云api_key（主要负责功能：Embedding、Rerank、通义对话等）
      embedding:
        model: text-embedding-v4
        dimension: 1024
      chat:
        model: qwen-plus
      rerank:
        model: gte-rerank-v2

ai:
  anthropic:
    api-key: your-anthropic-or-compatible-api-key # 这里可以申请代理，这边提供一种参考（主要负责功能：AI 聊天、PPT 生成、RAG 问答）
    base-url: https://aihubmix.com/v1
    chat:
      model: claude-haiku-4-5
    max-tokens: 4096

mybatis:
  mapper-locations: classpath:mapper/**/*.xml
  type-aliases-package: com.javaee.docmanager.user.entity,com.javaee.docmanager.file.entity,com.javaee.docmanager.doc.entity
  configuration:
    map-underscore-to-camel-case: true

minio:
  endpoint: http://localhost:9000
  access-key: minioadmin
  secret-key: minioadmin
  bucket-name: doc-ai
  secure: false

file:
  storage:
    type: minio
    local-path: ./data/file-storage
    max-size: 1024

qdrant:
  host: localhost
  rest-port: 6333

mcp:
  server:
    enabled: false
    url: http://localhost:3000/mcp

aiops:
  metrics:
    archive:
      cron: "0 5 0 * * ?"
  error-log:
    ai-analysis:
      enabled: true

springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html

logging:
  level:
    com.javaee.docmanager: debug

ppt:
  reference:
    primary-max-chars: 12000
    secondary-top-k: 6
```

> **安全提示**：请勿将含真实 API Key 和密码的 `application.yml` 提交到版本库。

以上内容可在 src\main\resources\applicaation.yml.example 中找到，可以直接使用这个文档进行配置，修改密码等，改完将后缀 .example 去掉即可

### 5. 编译并启动

```bash
mvn clean package -DskipTests
mvn spring-boot:run
```

或直接运行主类：

```
com.javaee.docmanager.DocManagerApplication
```

### 6. 访问应用

| 地址 | 说明 |
|------|------|
| http://localhost:8080 | Web 前端（内置 Vue 单页应用） |
| http://localhost:8080/swagger-ui.html | Swagger API 文档 |
| http://localhost:8080/api-docs | OpenAPI JSON |

首次使用请先调用 `POST /api/users/register` 注册账号，再通过 `POST /api/users/login` 登录获取 JWT Token。后续请求在 Header 中携带：

```
Authorization: Bearer <your-token>
```

---

## 项目结构

```
DocManager/
├── pom.xml                          # Maven 依赖与构建配置
├── src/main/java/com/javaee/docmanager/
│   ├── DocManagerApplication.java   # 启动入口
│   ├── user/                        # 用户模块（注册、登录、权限）
│   ├── file/                        # 文件模块（上传、下载、MinIO）
│   ├── doc/                         # 文档模块（版本、分支、合并）
│   ├── ai/                          # AI 模块（聊天、RAG、Agent、AIOps）
│   ├── security/                    # JWT 拦截与鉴权
│   ├── config/                      # 全局配置（Security、Kafka、缓存）
│   ├── cache/                       # Redis 分布式锁等
│   └── common/                      # 公共工具、异常、常量
└── src/main/resources/
    ├── doc_ai.sql                   # 数据库初始化脚本
    ├── mapper/                      # MyBatis XML 映射
    ├── skills/                      # Agent Skill 描述文档
    └── static/
        └── index.html               # 前端页面
```

---

## 主要 API 路由

| 前缀 | 功能 |
|------|------|
| `/api/users` | 用户注册、登录、信息管理 |
| `/api/files` | 通用文件上传/下载/元数据 |
| `/api/documents` | 文档库、版本、分支 |
| `/api/ai` | 摘要、关键词、分析 |
| `/api/ai/chat` | AI 对话、PPT 生成、生成文件管理 |
| `/api/ai/rag` | 知识库索引与检索问答 |
| `/api/ai/agent` | 智能 Agent 会话 |
| `/api/ai/aiops` | AI 运维监控 |
| `/api/skills` | Skill 技能调用 |

详细接口参数与响应格式请访问 Swagger UI。

---

## 常见问题

**Q：启动时报 MinIO / Redis / MySQL 连接失败？**  
A：请确认对应中间件已启动，且 `application.yml` 中的地址、端口、账号密码正确。

**Q：没有 Kafka 能否运行？**  
A：可以。项目对 Kafka 配置了较短超时，Broker 不可用时核心接口仍可正常使用，仅异步事件功能受影响。

**Q：RAG 功能无法使用？**  
A：请确认 Qdrant 已启动，且 DashScope `api-key` 配置正确；需先调用索引接口将文档写入知识库。

**Q：AI 聊天 / PPT 生成报错？**  
A：检查 `ai.anthropic.api-key` 与 `base-url` 是否有效，模型名称需与网关支持的 ID 一致。

**Q：JWT 密钥如何修改？**  
A：当前 JWT 密钥硬编码在 `JwtUtils.java` 中，生产环境建议改为可配置项并更换为足够长度的随机字符串。

---

## 许可证

本项目仅供学习与内部使用，具体授权方式请联系项目维护者。
