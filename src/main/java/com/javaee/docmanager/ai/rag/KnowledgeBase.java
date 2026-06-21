package com.javaee.docmanager.ai.rag;

import com.javaee.docmanager.ai.aiops.MonitoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库
 * 管理文档内容和元数据
 * 支持文档分块、混合检索
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);
    private static final String DOCUMENT_PREFIX = "doc:";
    /** 切片正文缓存（ES 未部署时的检索回退） */
    private static final String CHUNK_PREFIX = "chunk:";
    /** 文档关联的切片 ID 集合 */
    private static final String DOC_CHUNKS_PREFIX = "docchunks:";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private DocumentVectorizer vectorizer;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private DocumentChunker chunker;

    @Autowired
    private SemanticChunker semanticChunker;

    @Autowired
    private Reranker reranker;

    @Autowired
    private QueryRewriter queryRewriter;

    @Autowired
    private MonitoringService monitoringService;

    @Autowired(required = false)
    private ElasticsearchOperations elasticsearchOperations;

    /**
     * 添加文档到知识库（两阶段分块：结构化粗切分 + 语义细分块）
     */
    public void addDocument(String documentId, String content, Map<String, Object> metadata) {
        addDocument(documentId, content, null, metadata);
    }

    /**
     * 添加文档到知识库（支持原始文件数据用于PDF/Word按页/标题切分）
     */
    public void addDocument(String documentId, String content, byte[] rawData, Map<String, Object> metadata) {
        log.info("添加文档到知识库: documentId={}", documentId);

        try {
            String docKey = DOCUMENT_PREFIX + documentId;
            Map<String, Object> normalizedMetadata = normalizeMetadata(metadata);

            redisTemplate.opsForHash().putAll(docKey, normalizedMetadata);
            deleteChunks(documentId);

            String fileType = normalizedMetadata.getOrDefault("fileType", "").toString();
            String fileName = normalizedMetadata.getOrDefault("fileName", "").toString();

            // === 第一阶段：结构化粗切分 ===
            long t1 = System.currentTimeMillis();
            List<String> structuralChunks = chunker.chunkByStructure(content, rawData, fileType, fileName);
            log.info("[分块 Stage1 结构化切分] 耗时={}ms, 粗块数={}", System.currentTimeMillis() - t1, structuralChunks.size());

            // === 第二阶段：语义细分块 ===
            long t2 = System.currentTimeMillis();
            List<String> chunks = semanticChunker.chunk(structuralChunks);
            log.info("[分块 Stage2 语义切分] 耗时={}ms, 最终块数={}", System.currentTimeMillis() - t2, chunks.size());

            if (chunks.isEmpty()) {
                redisTemplate.delete(docKey);
                log.warn("文档分块为空，已回滚元数据: documentId={}", documentId);
                throw new RuntimeException("文档分块结果为空，无法索引");
            }

            String originalFileName = normalizedMetadata.getOrDefault("fileName", "").toString();

            // === 向量化并存储 ===
            int successCount = 0;
            for (int i = 0; i < chunks.size(); i++) {
                String chunkId = documentId + "::" + i;
                String chunkText = chunks.get(i);

                saveChunkToRedis(chunkId, chunkText);
                trackDocChunk(documentId, chunkId);
                String storedText = getChunkContent(chunkId);
                if (storedText == null || storedText.isBlank()) {
                    throw new RuntimeException("切片正文写入 Redis 失败: chunkId=" + chunkId);
                }
                indexToEs(chunkId, chunkText, documentId, originalFileName);

                try {
                    float[] vector = vectorizer.vectorize(chunkText);
                    vectorStore.store(chunkId, vector, normalizedMetadata);
                    successCount++;
                } catch (Exception e) {
                    log.warn("分块向量化失败，跳过: chunkId={}, error={}", chunkId, e.getMessage());
                }
            }

            log.info("文档添加成功: documentId={}, 粗块={}, 最终块={}, vectorized={}",
                    documentId, structuralChunks.size(), chunks.size(), successCount);
            if (successCount == 0) {
                redisTemplate.delete(docKey);
                throw new RuntimeException("向量化失败，文档未能写入检索索引，请检查向量服务配置后重试");
            }
            monitoringService.incrementCounter("rag.docs");
            monitoringService.incrementCounter("rag.slices", successCount);
        } catch (Exception e) {
            log.error("添加文档失败", e);
            throw new RuntimeException("添加文档失败: " + e.getMessage(), e);
        }
    }

    public void removeDocument(String documentId) {
        log.info("从知识库移除文档: documentId={}", documentId);
        try {
            Map<String, Object> meta = getDocumentMetadata(documentId);
            String fileName = metaString(meta, "fileName");
            String sourceDocumentId = metaString(meta, "sourceDocumentId");

            deleteChunks(documentId);
            deleteEsByDocId(documentId);
            vectorStore.deleteByDocumentId(documentId);

            if (sourceDocumentId != null && !sourceDocumentId.isBlank()) {
                vectorStore.deleteByPayloadMatch("sourceDocumentId", sourceDocumentId);
                String legacyId = "lib:" + sourceDocumentId;
                deleteChunks(legacyId);
                vectorStore.deleteByDocumentId(legacyId);
                redisTemplate.delete(DOCUMENT_PREFIX + legacyId);
            }
            if (fileName != null && !fileName.isBlank() && !isFileNameUsedByOtherDocument(fileName, documentId)) {
                vectorStore.deleteByPayloadMatch("fileName", fileName);
                deleteEsByFileName(fileName);
            }

            redisTemplate.delete(DOCUMENT_PREFIX + documentId);
            log.info("文档移除成功: documentId={}, fileName={}", documentId, fileName);
        } catch (Exception e) {
            log.error("移除文档失败", e);
            throw new RuntimeException("移除文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 清理文档库来源相关的全部向量（含历史 lib: 前缀与孤儿点）
     */
    public void purgeLibraryVectors(String sourceDocumentId, String ragDocumentId) {
        if (ragDocumentId != null && !ragDocumentId.isBlank()) {
            removeDocument(ragDocumentId);
        }
        removeDocument("lib:" + sourceDocumentId);
        vectorStore.deleteByPayloadMatch("sourceDocumentId", sourceDocumentId);
    }

    private void deleteChunks(String documentId) {
        Set<String> chunkIds = new LinkedHashSet<>(getChunkIdsByDocId(documentId));
        chunkIds.addAll(getChunkIdsFromRedis(documentId));
        for (String chunkId : chunkIds) {
            try {
                vectorStore.delete(chunkId);
            } catch (Exception e) {
                log.warn("Qdrant 向量删除失败: chunkId={}", chunkId);
            }
            deleteFromEs(chunkId);
            deleteChunkFromRedis(chunkId);
        }
        redisTemplate.delete(DOC_CHUNKS_PREFIX + documentId);
        stringRedisTemplate.delete(DOC_CHUNKS_PREFIX + documentId);
    }

    private void saveChunkToRedis(String chunkId, String content) {
        try {
            stringRedisTemplate.opsForValue().set(CHUNK_PREFIX + chunkId, content);
        } catch (Exception e) {
            log.warn("Redis 切片缓存写入失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    private void trackDocChunk(String documentId, String chunkId) {
        try {
            stringRedisTemplate.opsForSet().add(DOC_CHUNKS_PREFIX + documentId, chunkId);
        } catch (Exception e) {
            log.warn("Redis 切片索引写入失败: documentId={}, chunkId={}, error={}", documentId, chunkId, e.getMessage());
        }
    }

    private void deleteChunkFromRedis(String chunkId) {
        try {
            stringRedisTemplate.delete(CHUNK_PREFIX + chunkId);
            redisTemplate.delete(CHUNK_PREFIX + chunkId);
        } catch (Exception e) {
            log.warn("Redis 切片删除失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    private Set<String> getChunkIdsFromRedis(String documentId) {
        Set<String> chunkIds = new LinkedHashSet<>();
        try {
            Set<String> members = stringRedisTemplate.opsForSet().members(DOC_CHUNKS_PREFIX + documentId);
            if (members != null) {
                chunkIds.addAll(members);
            }
            if (chunkIds.isEmpty()) {
                Set<Object> legacyMembers = redisTemplate.opsForSet().members(DOC_CHUNKS_PREFIX + documentId);
                if (legacyMembers != null) {
                    for (Object member : legacyMembers) {
                        if (member != null) {
                            chunkIds.add(member.toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Redis 切片列表读取失败: documentId={}, error={}", documentId, e.getMessage());
        }
        return chunkIds;
    }

    public int countDocumentChunks(String documentId) {
        return getChunkIdsFromRedis(documentId).size();
    }

    /**
     * 从 ES 查询某文档的所有 chunkId
     */
    private List<String> getChunkIdsByDocId(String documentId) {
        List<String> chunkIds = new ArrayList<>();
        if (elasticsearchOperations == null) return chunkIds;
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.term(t -> t.field("docId").value(documentId)))
                    .withMaxResults(1000)
                    .build();
            SearchHits<ChunkDocument> hits = elasticsearchOperations.search(query, ChunkDocument.class);
            for (SearchHit<ChunkDocument> hit : hits) {
                chunkIds.add(hit.getContent().getChunkId());
            }
        } catch (Exception e) {
            log.warn("ES 查询 chunkId 列表失败: docId={}, error={}", documentId, e.getMessage());
        }
        return chunkIds;
    }

    public String getDocumentContent(String documentId) {
        try {
            if (elasticsearchOperations != null) {
                NativeQuery query = NativeQuery.builder()
                        .withQuery(q -> q.term(t -> t.field("docId").value(documentId)))
                        .withMaxResults(1000)
                        .build();
                SearchHits<ChunkDocument> hits = elasticsearchOperations.search(query, ChunkDocument.class);
                if (!hits.isEmpty()) {
                    List<ChunkDocument> docs = hits.stream()
                            .map(SearchHit::getContent)
                            .sorted(Comparator.comparingInt(d -> {
                                String id = d.getChunkId();
                                int idx = id.lastIndexOf("::");
                                return idx > 0 ? Integer.parseInt(id.substring(idx + 2)) : 0;
                            }))
                            .toList();

                    StringBuilder sb = new StringBuilder();
                    for (ChunkDocument doc : docs) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(doc.getContent());
                    }
                    return sb.toString();
                }
            }

            Set<String> chunkIds = getChunkIdsFromRedis(documentId);
            if (chunkIds.isEmpty()) {
                return null;
            }
            List<String> sorted = new ArrayList<>(chunkIds);
            sorted.sort(Comparator.comparingInt(id -> {
                int idx = id.lastIndexOf("::");
                return idx > 0 ? Integer.parseInt(id.substring(idx + 2)) : 0;
            }));
            StringBuilder sb = new StringBuilder();
            for (String chunkId : sorted) {
                String content = getChunkContent(chunkId);
                if (content != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(content);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("获取文档内容失败", e);
            return null;
        }
    }

    public Map<String, Object> getDocumentMetadata(String documentId) {
        try {
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(DOCUMENT_PREFIX + documentId);
            Map<String, Object> metadata = new HashMap<>();
            for (Map.Entry<Object, Object> entry : hash.entrySet()) {
                metadata.put(entry.getKey().toString(), entry.getValue());
            }
            return metadata;
        } catch (Exception e) {
            log.warn("获取文档元数据失败", e);
            return Collections.emptyMap();
        }
    }

    /**
     * 基础向量检索
     */
    public List<Map<String, Object>> search(String query, int topK) {
        log.info("向量检索: query={}, topK={}", query, topK);
        try {
            // 查询改写：用改写后的中文语义查询做向量检索
            QueryRewriter.QueryParts parts = queryRewriter.extract(query);
            float[] queryVector = vectorizer.vectorize(parts.rewrittenForVector);
            List<Map<String, Object>> rawResults = vectorStore.search(queryVector, topK);
            for (Map<String, Object> r : rawResults) {
                String chunkId = (String) r.get("id");
                String content = getExpandedContent(chunkId);
                if (content == null || content.isBlank()) {
                    purgeOrphanVector(chunkId);
                    continue;
                }
                String docId = resolveDocumentId(chunkId, r);
                r.put("chunkId", chunkId);
                r.put("content", content);
                r.put("id", chunkId);
                r.put("documentId", docId);
                r.put("fileName", resolveFileName(chunkId, r));
            }
            return rawResults.stream()
                    .filter(r -> r.get("content") != null)
                    .toList();
        } catch (Exception e) {
            log.error("向量检索失败", e);
            throw new RuntimeException("向量检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 混合检索加重排序
     */
    private static final float RERANK_SCORE_THRESHOLD = 0.35f;

    public List<Map<String, Object>> hybridSearchWithRerank(String query, int topK,
                                                             Reranker.RerankStrategy strategy) {
        log.info("混合检索加重排序: query={}, topK={}, strategy={}", query, topK, strategy);
        List<Map<String, Object>> hybridResults = hybridSearch(query, topK * 3);
        // 确保有 similarity 字段供 Reranker 使用
        for (Map<String, Object> r : hybridResults) {
            if (!r.containsKey("similarity")) {
                r.put("similarity", r.getOrDefault("score", 0.0f));
            }
        }
        List<Map<String, Object>> reranked = reranker.rerank(query, hybridResults, strategy, topK);

        // Rerank 后按 rerank 分数阈值过滤；若全部被过滤则降级返回 hybrid 结果
        if (strategy == Reranker.RerankStrategy.DASHSCOPE_RERANK) {
            int before = reranked.size();
            List<Map<String, Object>> filtered = reranked.stream()
                    .filter(r -> {
                        Object score = r.get("rerankScore");
                        return score != null && ((Number) score).floatValue() >= RERANK_SCORE_THRESHOLD;
                    })
                    .collect(java.util.stream.Collectors.toList());
            log.info("Rerank 阈值过滤: 阈值={}, 前={}, 后={}", RERANK_SCORE_THRESHOLD, before, filtered.size());
            if (!filtered.isEmpty()) {
                reranked = filtered;
            } else if (!hybridResults.isEmpty()) {
                log.warn("Rerank 阈值过滤后无结果，降级返回 hybrid 检索 Top{}", topK);
                reranked = hybridResults.stream().limit(topK).toList();
            } else {
                reranked = filtered;
            }
        }
        return filterContextResults(query, reranked);
    }

    /**
     * 仅保留与问题相关、且属于当前仍有效索引文档的片段，供 LLM 生成答案。
     */
    public List<Map<String, Object>> filterContextResults(String query, List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<String> sigKeywords = extractSignificantKeywords(query);
        List<Map<String, Object>> relevant = new ArrayList<>();
        Map<String, Map<String, Object>> bestActivePerDoc = new LinkedHashMap<>();

        for (Map<String, Object> r : results) {
            String chunkKey = String.valueOf(r.getOrDefault("chunkId", r.get("id")));
            String docId = resolveDocumentId(chunkKey, r);
            if (!isActiveDocument(docId)) {
                purgeOrphanVector(chunkKey);
                continue;
            }
            String content = (String) r.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            float rerankScore = toFloat(r.get("rerankScore"));
            float score = toFloat(r.getOrDefault("score", r.get("similarity")));
            float rankScore = Math.max(rerankScore, score);

            Map<String, Object> currentBest = bestActivePerDoc.get(docId);
            if (currentBest == null || rankScore > Math.max(
                    toFloat(currentBest.get("rerankScore")),
                    toFloat(currentBest.getOrDefault("score", currentBest.get("similarity"))))) {
                bestActivePerDoc.put(docId, r);
            }

            if (rerankScore >= RERANK_SCORE_THRESHOLD || score >= 0.45f) {
                relevant.add(r);
                continue;
            }
            if (matchesSignificantKeywords(sigKeywords, content)) {
                relevant.add(r);
            }
        }

        LinkedHashSet<String> coveredDocs = new LinkedHashSet<>();
        for (Map<String, Object> r : relevant) {
            coveredDocs.add(resolveDocumentId(
                    String.valueOf(r.getOrDefault("chunkId", r.get("id"))), r));
        }
        for (Map.Entry<String, Map<String, Object>> entry : bestActivePerDoc.entrySet()) {
            if (!coveredDocs.contains(entry.getKey())) {
                relevant.add(entry.getValue());
                coveredDocs.add(entry.getKey());
            }
        }

        if (!relevant.isEmpty()) {
            return limitChunksPerDocument(relevant, 8, MAX_CHUNKS_PER_DOCUMENT);
        }
        List<Map<String, Object>> activeOnly = results.stream()
                .filter(r -> isActiveDocument(resolveDocumentId(
                        String.valueOf(r.getOrDefault("chunkId", r.get("id"))), r)))
                .toList();
        return limitChunksPerDocument(activeOnly, Math.min(4, activeOnly.size()), MAX_CHUNKS_PER_DOCUMENT);
    }

    private List<Map<String, Object>> limitChunksPerDocument(List<Map<String, Object>> results,
                                                             int maxTotal, int maxPerDoc) {
        List<Map<String, Object>> limited = new ArrayList<>();
        Map<String, Integer> perDoc = new HashMap<>();
        for (Map<String, Object> r : results) {
            if (limited.size() >= maxTotal) {
                break;
            }
            String docId = resolveDocumentId(
                    String.valueOf(r.getOrDefault("chunkId", r.get("id"))), r);
            int used = perDoc.getOrDefault(docId, 0);
            if (used >= maxPerDoc) {
                continue;
            }
            perDoc.put(docId, used + 1);
            limited.add(r);
        }
        return limited;
    }

    private static final float MIN_SCORE_THRESHOLD = 0.3f;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 2;

    /**
     * 动态计算向量/关键词融合权重
     * 根据查询特征自适应：包含技术词、数字、型号等精确实体时提高关键词权重
     */
    private float[] computeFusionWeights(String query, boolean hasTechTerms) {
        if (query == null || query.isBlank()) {
            return new float[]{0.8f, 0.2f};
        }

        String lower = query.toLowerCase();
        int specificityScore = 0;

        // 包含技术词（由 QueryRewriter 检测的英文标识符、版本号等）
        if (hasTechTerms) {
            specificityScore += 2;
        }
        // 包含数字（型号、版本、规格参数）
        if (lower.matches(".*\\d+.*")) {
            specificityScore += 1;
        }
        // 包含精确查询关键词（按领域分组，覆盖 Java 后端 + Agent 开发）
        String[] specificPatterns = {
                // 通用
                "型号", "版本", "参数", "配置", "价格", "规格", "编号", "方法", "接口", "函数",
                // Java 核心
                "类", "对象", "注解", "异常", "泛型", "枚举", "继承", "实现", "重写", "重载",
                "抽象", "接口", "内部类", "匿名类", "静态", "final", "构造器", "析构", "装箱", "拆箱",
                "集合", "列表", "映射", "集合", "迭代器", "比较器", "流", "Optional", "Record",
                // JVM
                "堆", "栈", "gc", "垃圾回收", "类加载", "双亲委派", "字节码", "jit", "调优", "oom",
                "内存泄漏", "栈溢出", "新生代", "老年代", "可达性分析",
                // 并发
                "线程", "并发", "锁", "队列", "线程池", "死锁", "活锁", "信号量", "屏障", "原子类",
                "volatile", "synchronized", "cas", "aqs", "阻塞", "非阻塞", "协程",
                // Spring
                "启动", "注入", "依赖", "切面", "代理", "反射", "回调", "监听器", "过滤器", "拦截器",
                "bean", "ioc", "aop", "mvc", "boot", "cloud", "starter", "自动装配", "条件注解",
                "事务", "传播", "隔离", "声明式", "编程式",
                // 数据库
                "索引", "查询", "事务", "连接池", "序列化", "慢查询", "执行计划", "分库", "分表",
                "主从", "读写分离", "迁移", "回滚", "死锁", "行锁", "表锁", "mvcc",
                // 缓存/消息
                "缓存", "穿透", "击穿", "雪崩", "淘汰", "过期", "持久化", "哨兵", "集群", "分片",
                "消息", "队列", "topic", "消费", "生产", "ack", "重试", "幂等", "死信", "延迟",
                // 网络/Web
                "请求", "响应", "路由", "中间件", "跨域", "csrf", "xss", "jwt", "oauth", "session",
                "cookie", "token", "刷新", "限流", "降级", "熔断", "负载", "网关",
                // 微服务
                "注册", "发现", "配置中心", "链路追踪", "服务网格", "sidecar", "熔断器", "重试",
                // 运维
                "部署", "容器", "日志", "监控", "告警", "ci", "cd", "流水线", "蓝绿", "金丝雀",
                "健康检查", "优雅停机", "滚动更新",
                // 测试
                "单元测试", "集成测试", "mock", "断言", "覆盖率", "测试用例", "回归", "压测", "基准",
                // Agent/LLM
                "向量", "embedding", "rerank", "重排", "召回", "精度", "切片", "分块", "chunk",
                "prompt", "提示词", "system", "few-shot", "zero-shot", "思维链", "cot",
                "rag", "知识库", "检索增强", "混合检索", "语义", "余弦相似度", "hnsw",
                "agent", "工具调用", "function call", "mcp", "上下文", "记忆", "会话",
                "大模型", "llm", "推理", "微调", "fine-tune", "量化", "蒸馏", "对齐",
                "幻觉", "hallucination", "评测", "benchmark", "ab测试",
                // 工具/框架
                "maven", "gradle", "git", "docker", "k8s", "kubernetes", "nginx", "jmeter",
                "mybatis", "jpa", "hibernate", "redis", "elasticsearch", "kafka", "rabbitmq",
                "minio", "oss", "s3", "prometheus", "grafana", "skywalking"
        };
        for (String pattern : specificPatterns) {
            if (lower.contains(pattern)) {
                specificityScore += 1;
            }
        }
        // 短查询（<=6字）适合关键词匹配
        if (query.length() <= 6) {
            specificityScore += 1;
        }

        if (specificityScore >= 4) {
            return new float[]{0.5f, 0.5f};
        } else if (specificityScore >= 2) {
            return new float[]{0.7f, 0.3f};
        } else {
            return new float[]{0.8f, 0.2f};
        }
    }

    /**
     * 混合检索（向量 + 关键词，返回分块级别的结果）
     */
    public List<Map<String, Object>> hybridSearch(String query, int topK) {
        log.info("===== 混合检索开始: query={}, topK={} =====", query, topK);
        long totalStart = System.currentTimeMillis();

        try {
            // Step 1: 查询改写
            long t1 = System.currentTimeMillis();
            QueryRewriter.QueryParts parts = queryRewriter.extract(query);
            log.info("[Step1 查询改写] 耗时={}ms, 改写后='{}', 技术词={}, keywordQuery='{}'",
                    System.currentTimeMillis() - t1, parts.rewrittenForVector, parts.hasTechTerms(), parts.getKeywordQuery());

            // Step 2: 计算融合权重
            float[] weights = computeFusionWeights(query, parts.hasTechTerms());
            float vecWeight = weights[0];
            float kwWeight = weights[1];
            log.info("[Step2 融合权重] vector={}, keyword={}", vecWeight, kwWeight);

            // Step 3: 向量检索
            long t3 = System.currentTimeMillis();
            float[] queryVector = vectorizer.vectorize(parts.rewrittenForVector);
            List<Map<String, Object>> vectorResults = vectorStore.search(queryVector, topK * 10);
            log.info("[Step3 向量检索] 耆时={}ms, 结果数={}", System.currentTimeMillis() - t3, vectorResults.size());
            for (int i = 0; i < Math.min(3, vectorResults.size()); i++) {
                Map<String, Object> r = vectorResults.get(i);
                log.info("  向量Top{}: chunkId={}, similarity={}", i + 1, r.get("id"), r.get("similarity"));
            }

            // Step 4: 关键词检索
            long t4 = System.currentTimeMillis();
            String kwQuery = parts.hasTechTerms() ? parts.getKeywordQuery() : query;
            List<Map<String, Object>> keywordResults = keywordSearch(kwQuery, topK * 5);
            List<Map<String, Object>> redisKeywordResults = redisKeywordSearchChunks(kwQuery, topK * 5);
            keywordResults = mergeKeywordResults(keywordResults, redisKeywordResults);
            log.info("[Step4 关键词检索] 耗时={}ms, query='{}', 结果数={}", System.currentTimeMillis() - t4, kwQuery, keywordResults.size());
            for (int i = 0; i < Math.min(3, keywordResults.size()); i++) {
                Map<String, Object> r = keywordResults.get(i);
                log.info("  关键词Top{}: chunkId={}, score={}", i + 1, r.get("id"), r.get("similarity"));
            }

            // Step 5: 融合
            long t5 = System.currentTimeMillis();
            Map<String, Float> scoreMap = new LinkedHashMap<>();
            Map<String, Map<String, Object>> resultMap = new HashMap<>();

            for (Map<String, Object> r : vectorResults) {
                String chunkId = (String) r.get("id");
                float vecScore = ((Number) r.get("similarity")).floatValue();
                scoreMap.put(chunkId, vecScore * vecWeight);
                resultMap.put(chunkId, r);
            }

            for (Map<String, Object> r : keywordResults) {
                String chunkId = (String) r.get("id");
                float kwScore = ((Number) r.get("similarity")).floatValue();
                Float existing = scoreMap.get(chunkId);
                if (existing != null) {
                    scoreMap.put(chunkId, existing + kwScore * kwWeight);
                } else {
                    scoreMap.put(chunkId, kwScore * kwWeight);
                    resultMap.put(chunkId, r);
                }
            }

            // Step 5.5: 按文档补充检索，避免大文档独占全部候选位
            mergePerDocumentCandidates(queryVector, kwQuery, vecWeight, kwWeight, scoreMap, resultMap);

            List<Map.Entry<String, Float>> sorted = new ArrayList<>(scoreMap.entrySet());
            sorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

            List<Map<String, Object>> results = collectHybridResultsWithDiversity(sorted, resultMap, topK, MIN_SCORE_THRESHOLD);
            if (results.isEmpty() && !sorted.isEmpty()) {
                log.warn("融合分数均低于阈值 {}，降级返回最高分片段", MIN_SCORE_THRESHOLD);
                results = collectHybridResultsWithDiversity(sorted, resultMap, topK, 0f);
            }
            if (results.isEmpty()) {
                results = redisKeywordFallback(kwQuery, topK);
                log.info("[Step5 Redis关键词回退] 结果数={}", results.size());
            }
            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> r = results.get(i);
                String content = (String) r.get("content");
                String preview = content != null ? content.substring(0, Math.min(80, content.length())).replace("\n", " ") : "null";
                log.info("  最终Top{}: chunkId={}, score={}, fileName={}, 内容预览='{}'",
                        i + 1, r.get("chunkId"), r.get("score"), r.get("fileName"), preview);
            }

            log.info("===== 混合检索完成: 总耗时={}ms =====", System.currentTimeMillis() - totalStart);
            return results;

        } catch (Exception e) {
            log.error("混合检索失败", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为每个已索引文档补充向量/关键词候选，防止单个超大文档挤占全部检索名额。
     */
    private void mergePerDocumentCandidates(float[] queryVector, String query,
                                            float vecWeight, float kwWeight,
                                            Map<String, Float> scoreMap,
                                            Map<String, Map<String, Object>> resultMap) {
        List<String> keywords = extractKeywords(query);
        for (String docId : getAllDocumentIds()) {
            Set<String> chunkIds = getChunkIdsFromRedis(docId);
            if (chunkIds.isEmpty()) {
                continue;
            }

            List<Map<String, Object>> docVecHits = vectorStore.searchByDocumentId(queryVector, docId, 3);
            if (docVecHits.isEmpty()) {
                log.info("按文档向量检索无结果，将依赖关键词回退: documentId={}", docId);
            }
            for (Map<String, Object> hit : docVecHits) {
                String chunkId = (String) hit.get("id");
                float vecScore = ((Number) hit.get("similarity")).floatValue();
                float fused = Math.max(vecScore * vecWeight, MIN_SCORE_THRESHOLD);
                Float existing = scoreMap.get(chunkId);
                scoreMap.put(chunkId, existing != null ? Math.max(existing, fused) : fused);
                resultMap.put(chunkId, hit);
            }

            String bestKeywordChunk = null;
            int bestKeywordHits = 0;
            for (String chunkId : chunkIds) {
                String content = getChunkContent(chunkId);
                if (content == null || content.isBlank()) {
                    continue;
                }
                int hitCount = countKeywordHits(keywords, content);
                if (hitCount > bestKeywordHits) {
                    bestKeywordHits = hitCount;
                    bestKeywordChunk = chunkId;
                }
                if (hitCount == 0) {
                    continue;
                }
                if (!isMeaningfulKeywordMatch(hitCount, keywords, content)) {
                    continue;
                }
                float keywordScore = 0.45f + Math.min(0.25f, hitCount * 0.05f);
                Float existing = scoreMap.get(chunkId);
                if (existing == null) {
                    resultMap.put(chunkId, buildDocChunkCandidate(docId, chunkId));
                    scoreMap.put(chunkId, keywordScore);
                } else {
                    scoreMap.put(chunkId, Math.max(existing, keywordScore));
                }
            }

            // 文档库导入的文档若未进入全局 Top-N，且向量过滤检索失败，保留关键词最优切片
            if (!hasDocumentCandidate(scoreMap, resultMap, docId)
                    && bestKeywordChunk != null
                    && isMeaningfulKeywordMatch(bestKeywordHits, keywords, getChunkContent(bestKeywordChunk))) {
                float keywordScore = 0.5f + Math.min(0.2f, bestKeywordHits * 0.05f);
                resultMap.putIfAbsent(bestKeywordChunk, buildDocChunkCandidate(docId, bestKeywordChunk));
                scoreMap.put(bestKeywordChunk, Math.max(scoreMap.getOrDefault(bestKeywordChunk, 0f), keywordScore));
                log.info("文档关键词保底召回: documentId={}, chunkId={}, keywordHits={}",
                        docId, bestKeywordChunk, bestKeywordHits);
            }
        }
    }

    private Map<String, Object> buildDocChunkCandidate(String docId, String chunkId) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", chunkId);
        item.put("chunkId", chunkId);
        item.put("documentId", docId);
        item.put("fileName", getDocMetadata(docId, "fileName"));
        item.put("sourceType", getDocMetadata(docId, "sourceType"));
        item.put("libraryTitle", getDocMetadata(docId, "libraryTitle"));
        return item;
    }

    private boolean hasDocumentCandidate(Map<String, Float> scoreMap,
                                         Map<String, Map<String, Object>> resultMap,
                                         String docId) {
        for (String chunkId : scoreMap.keySet()) {
            if (docId.equals(resolveDocumentId(chunkId, resultMap.get(chunkId)))) {
                return true;
            }
        }
        return false;
    }

    private int countKeywordHits(List<String> keywords, String content) {
        int hits = 0;
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && content.contains(kw)) {
                hits++;
            }
        }
        return hits;
    }

    private List<Map<String, Object>> mergeKeywordResults(List<Map<String, Object>> esResults,
                                                          List<Map<String, Object>> redisResults) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : esResults) {
            merged.put((String) r.get("id"), r);
        }
        for (Map<String, Object> r : redisResults) {
            String chunkId = (String) r.get("id");
            if (!merged.containsKey(chunkId)) {
                merged.put(chunkId, r);
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * 多文档多样性选取：每个文档先取最优片段，再按分数补齐。
     */
    private List<Map<String, Object>> collectHybridResultsWithDiversity(
            List<Map.Entry<String, Float>> sorted,
            Map<String, Map<String, Object>> resultMap,
            int topK, float minScore) {
        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> pickedChunks = new HashSet<>();

        Map<String, Map.Entry<String, Float>> bestPerDoc = new LinkedHashMap<>();
        for (Map.Entry<String, Float> entry : sorted) {
            if (entry.getValue() < minScore) {
                continue;
            }
            String chunkId = entry.getKey();
            String docId = resolveDocumentId(chunkId, resultMap.get(chunkId));
            Map.Entry<String, Float> current = bestPerDoc.get(docId);
            if (current == null || entry.getValue() > current.getValue()) {
                bestPerDoc.put(docId, entry);
            }
        }

        List<Map.Entry<String, Float>> docBestSorted = new ArrayList<>(bestPerDoc.values());
        docBestSorted.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Float> entry : docBestSorted) {
            if (results.size() >= topK) {
                break;
            }
            Map<String, Object> item = buildHybridResultItem(entry, resultMap);
            if (item == null) {
                continue;
            }
            results.add(item);
            pickedChunks.add(entry.getKey());
        }

        for (Map.Entry<String, Float> entry : sorted) {
            if (results.size() >= topK) {
                break;
            }
            if (entry.getValue() < minScore || pickedChunks.contains(entry.getKey())) {
                continue;
            }
            String docId = resolveDocumentId(entry.getKey(), resultMap.get(entry.getKey()));
            long sameDocCount = results.stream()
                    .filter(r -> docId.equals(r.get("documentId")))
                    .count();
            if (sameDocCount >= MAX_CHUNKS_PER_DOCUMENT) {
                continue;
            }
            Map<String, Object> item = buildHybridResultItem(entry, resultMap);
            if (item == null) {
                continue;
            }
            results.add(item);
            pickedChunks.add(entry.getKey());
        }
        return results;
    }

    private Map<String, Object> buildHybridResultItem(Map.Entry<String, Float> entry,
                                                      Map<String, Map<String, Object>> resultMap) {
        String chunkId = entry.getKey();
        String content = getExpandedContent(chunkId);
        if (content == null || content.isBlank()) {
            purgeOrphanVector(chunkId);
            return null;
        }
        String docId = resolveDocumentId(chunkId, resultMap.get(chunkId));
        if (!isActiveDocument(docId)) {
            purgeOrphanVector(chunkId);
            deleteFromEs(chunkId);
            return null;
        }
        Map<String, Object> item = new HashMap<>(resultMap.getOrDefault(chunkId, Map.of()));
        item.put("id", chunkId);
        item.put("documentId", docId);
        item.put("chunkId", chunkId);
        item.put("content", content);
        item.put("score", entry.getValue());
        item.put("similarity", entry.getValue());
        item.put("fileName", resolveFileName(chunkId, item));
        return item;
    }

    private List<Map<String, Object>> collectHybridResults(List<Map.Entry<String, Float>> sorted,
                                                            Map<String, Map<String, Object>> resultMap,
                                                            int topK, float minScore) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, Float> entry : sorted) {
            if (results.size() >= topK) {
                break;
            }
            if (entry.getValue() < minScore) {
                continue;
            }
            String chunkId = entry.getKey();
            String content = getExpandedContent(chunkId);
            if (content == null || content.isBlank()) {
                purgeOrphanVector(chunkId);
                continue;
            }
            String docId = resolveDocumentId(chunkId, resultMap.get(chunkId));
            Map<String, Object> item = new HashMap<>(resultMap.getOrDefault(chunkId, Map.of()));
            item.put("id", chunkId);
            item.put("documentId", docId);
            item.put("chunkId", chunkId);
            item.put("content", content);
            item.put("score", entry.getValue());
            item.put("similarity", entry.getValue());
            item.put("fileName", resolveFileName(chunkId, item));
            results.add(item);
        }
        return results;
    }

    /**
     * ES / 向量均未命中时，在 Redis 切片中做关键词子串回退检索。
     */
    private List<Map<String, Object>> redisKeywordFallback(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> keywords = extractKeywords(query);
        List<Map<String, Object>> hits = new ArrayList<>();
        try {
            for (String docId : getAllDocumentIds()) {
                for (String chunkId : getChunkIdsFromRedis(docId)) {
                    String content = getChunkContent(chunkId);
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    boolean matched = keywords.stream().anyMatch(kw ->
                            kw != null && !kw.isBlank() && content.contains(kw));
                    if (!matched) {
                        continue;
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", docId);
                    item.put("documentId", docId);
                    item.put("chunkId", chunkId);
                    item.put("content", getExpandedContent(chunkId));
                    item.put("score", 0.55f);
                    item.put("similarity", 0.55f);
                    item.put("fileName", resolveFileName(chunkId, Map.of(
                            "fileName", getDocMetadata(docId, "fileName"),
                            "libraryTitle", getDocMetadata(docId, "libraryTitle"))));
                    hits.add(item);
                    if (hits.size() >= topK) {
                        return hits;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Redis 关键词回退检索失败: {}", e.getMessage());
        }
        return hits;
    }

    private static final Pattern CJK_SEGMENT = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    private List<String> extractKeywords(String query) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (query == null) {
            return List.of();
        }
        String trimmed = query.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }
        keys.add(trimmed);
        for (String part : trimmed.split("[\\s,，.。?？!！;；、]+")) {
            if (part != null && part.length() >= 2) {
                keys.add(part);
            }
        }
        Matcher matcher = CJK_SEGMENT.matcher(trimmed);
        while (matcher.find()) {
            String segment = matcher.group();
            keys.add(segment);
            if (segment.length() > 3) {
                for (int len = 3; len <= Math.min(4, segment.length()); len++) {
                    for (int i = 0; i <= segment.length() - len; i++) {
                        keys.add(segment.substring(i, i + len));
                    }
                }
            }
        }
        return new ArrayList<>(keys);
    }

    private List<String> extractSignificantKeywords(String query) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String kw : extractKeywords(query)) {
            if (kw != null && kw.length() >= 3) {
                keys.add(kw);
            }
        }
        if (query != null) {
            for (String part : query.trim().split("[\\s,，.。?？!！;；、]+")) {
                if (part != null && part.length() >= 2) {
                    keys.add(part);
                }
            }
        }
        return new ArrayList<>(keys);
    }

    private boolean matchesSignificantKeywords(List<String> keywords, String content) {
        if (content == null || content.isBlank() || keywords.isEmpty()) {
            return false;
        }
        for (String kw : keywords) {
            if (kw != null && kw.length() >= 2 && content.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMeaningfulKeywordMatch(int hitCount, List<String> keywords, String content) {
        if (hitCount <= 0 || content == null || content.isBlank()) {
            return false;
        }
        int longest = 0;
        for (String kw : keywords) {
            if (kw != null && content.contains(kw)) {
                longest = Math.max(longest, kw.length());
            }
        }
        return hitCount >= 2 || longest >= 4;
    }

    private List<Map<String, Object>> redisKeywordSearchChunks(String query, int topK) {
        List<Map<String, Object>> fallback = redisKeywordFallback(query, topK);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (Map<String, Object> item : fallback) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", item.get("chunkId"));
            m.put("similarity", item.get("similarity"));
            chunks.add(m);
        }
        return chunks;
    }

    /**
     * 关键词检索（ES 倒排索引 + IK 分词，O(1) 复杂度）
     */
    private List<Map<String, Object>> keywordSearch(String query, int topK) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (query == null || query.isBlank()) return results;
        if (elasticsearchOperations == null) return results;

        try {
            // 用 bool query: content 字段 match + fileName 字段 boost
            NativeQuery searchQuery = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> b
                            .should(s -> s.match(m -> m
                                    .field("content")
                                    .query(query)))
                            .should(s -> s.match(m -> m
                                    .field("fileName")
                                    .query(query)
                                    .boost(2.0f)))
                    ))
                    .withMaxResults(topK)
                    .build();

            SearchHits<ChunkDocument> hits = elasticsearchOperations.search(searchQuery, ChunkDocument.class);

            for (SearchHit<ChunkDocument> hit : hits) {
                ChunkDocument doc = hit.getContent();
                Map<String, Object> item = new HashMap<>();
                item.put("id", doc.getChunkId());
                item.put("similarity", hit.getScore());
                results.add(item);
            }

            log.info("ES 关键词检索: query={}, 结果数={}", query, results.size());
        } catch (Exception e) {
            log.warn("ES 检索失败: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 将 chunk 写入 ES 倒排索引
     */
    private void indexToEs(String chunkId, String content, String docId, String fileName) {
        if (elasticsearchOperations == null) return;
        try {
            ChunkDocument doc = new ChunkDocument(chunkId, content, docId, fileName);
            elasticsearchOperations.save(doc);
            log.debug("[indexToEs] 写入ES: chunkId={}, content长度={}, fileName={}", chunkId, content != null ? content.length() : 0, fileName);
        } catch (Exception e) {
            log.warn("ES 索引写入失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    /**
     * 从 ES 删除 chunk
     */
    private void deleteFromEs(String chunkId) {
        if (elasticsearchOperations == null) return;
        try {
            elasticsearchOperations.delete(chunkId, ChunkDocument.class);
        } catch (Exception e) {
            log.warn("ES 删除失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    private void deleteEsByDocId(String documentId) {
        if (elasticsearchOperations == null || documentId == null || documentId.isBlank()) {
            return;
        }
        try {
            List<String> chunkIds = getChunkIdsByDocId(documentId);
            for (String chunkId : chunkIds) {
                deleteFromEs(chunkId);
            }
        } catch (Exception e) {
            log.warn("ES 按文档删除失败: docId={}, error={}", documentId, e.getMessage());
        }
    }

    private void deleteEsByFileName(String fileName) {
        if (elasticsearchOperations == null || fileName == null || fileName.isBlank()) {
            return;
        }
        try {
            NativeQuery query = NativeQuery.builder()
                    .withQuery(q -> q.term(t -> t.field("fileName").value(fileName)))
                    .withMaxResults(2000)
                    .build();
            SearchHits<ChunkDocument> hits = elasticsearchOperations.search(query, ChunkDocument.class);
            for (SearchHit<ChunkDocument> hit : hits) {
                deleteFromEs(hit.getContent().getChunkId());
            }
        } catch (Exception e) {
            log.warn("ES 按文件名删除失败: fileName={}, error={}", fileName, e.getMessage());
        }
    }

    private boolean isActiveDocument(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return false;
        }
        Map<String, Object> meta = getDocumentMetadata(documentId);
        return meta != null && !meta.isEmpty();
    }

    private boolean isFileNameUsedByOtherDocument(String fileName, String excludeDocumentId) {
        for (String docId : getAllDocumentIds()) {
            if (docId.equals(excludeDocumentId)) {
                continue;
            }
            String existing = metaString(getDocumentMetadata(docId), "fileName");
            if (fileName.equals(existing)) {
                return true;
            }
        }
        return false;
    }

    private static String metaString(Map<String, Object> meta, String key) {
        if (meta == null || key == null) {
            return null;
        }
        Object val = meta.get(key);
        return val == null ? null : val.toString();
    }

    private static float toFloat(Object value) {
        if (value instanceof Number n) {
            return n.floatValue();
        }
        if (value == null) {
            return 0f;
        }
        try {
            return Float.parseFloat(value.toString());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private String resolveDocumentId(String chunkId, Map<String, Object> candidate) {
        if (candidate != null) {
            Object docId = candidate.get("documentId");
            if (docId != null && !docId.toString().isBlank()) {
                return docId.toString();
            }
            Object ragId = candidate.get("ragDocumentId");
            if (ragId != null && !ragId.toString().isBlank()) {
                return ragId.toString();
            }
        }
        return extractDocId(chunkId);
    }

    private void purgeOrphanVector(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return;
        }
        try {
            vectorStore.delete(chunkId);
            log.info("清理无正文的孤儿向量: chunkId={}", chunkId);
        } catch (Exception e) {
            log.debug("清理孤儿向量失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    private String extractDocId(String chunkId) {
        int idx = chunkId.lastIndexOf("::");
        return idx > 0 ? chunkId.substring(0, idx) : chunkId;
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        Map<String, Object> normalized = new HashMap<>();
        if (metadata == null) {
            return normalized;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            normalized.put(entry.getKey(), entry.getValue().toString());
        }
        return normalized;
    }

    private String getChunkContent(String chunkId) {
        try {
            String text = stringRedisTemplate.opsForValue().get(CHUNK_PREFIX + chunkId);
            if (text != null && !text.isBlank()) {
                return text;
            }
        } catch (Exception e) {
            log.warn("[getChunkContent] StringRedis get异常: chunkId={}, error={}", chunkId, e.getMessage());
        }

        try {
            Object cached = redisTemplate.opsForValue().get(CHUNK_PREFIX + chunkId);
            String legacy = extractStoredText(cached);
            if (legacy != null && !legacy.isBlank()) {
                stringRedisTemplate.opsForValue().set(CHUNK_PREFIX + chunkId, legacy);
                return legacy;
            }
        } catch (Exception e) {
            log.warn("[getChunkContent] Legacy Redis get异常: chunkId={}, error={}", chunkId, e.getMessage());
        }

        if (elasticsearchOperations != null) {
            try {
                ChunkDocument doc = elasticsearchOperations.get(chunkId, ChunkDocument.class);
                if (doc != null && doc.getContent() != null && !doc.getContent().isBlank()) {
                    stringRedisTemplate.opsForValue().set(CHUNK_PREFIX + chunkId, doc.getContent());
                    return doc.getContent();
                }
            } catch (Exception e) {
                log.warn("[getChunkContent] ES get异常: chunkId={}, error={}", chunkId, e.getMessage());
            }
        }

        log.warn("[getChunkContent] 未找到切片内容: chunkId={}", chunkId);
        return null;
    }

    private String extractStoredText(Object cached) {
        if (cached == null) {
            return null;
        }
        if (cached instanceof String s) {
            return s;
        }
        String text = cached.toString();
        if (text.startsWith("[") && text.contains("java.lang.String")) {
            int lastQuote = text.lastIndexOf('"');
            int prevQuote = text.lastIndexOf('"', lastQuote - 1);
            if (prevQuote >= 0 && lastQuote > prevQuote) {
                return text.substring(prevQuote + 1, lastQuote);
            }
        }
        return text;
    }

    /**
     * 扩展上下文：返回当前 chunk + 前后邻居 chunk 的拼接内容
     * 小块检索保证精度，邻块扩展保证上下文完整性
     */
    private String getExpandedContent(String chunkId) {
        String baseContent = getChunkContent(chunkId);
        if (baseContent == null) return null;

        String docId = extractDocId(chunkId);
        int idx = chunkId.lastIndexOf("::");
        if (idx < 0) return baseContent;
        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(chunkId.substring(idx + 2));
        } catch (NumberFormatException e) {
            return baseContent;
        }

        StringBuilder expanded = new StringBuilder();

        // 前一个 chunk
        String prevContent = getChunkContent(docId + "::" + (chunkIndex - 1));
        if (prevContent != null) {
            expanded.append(prevContent).append("\n\n");
        }

        // 当前 chunk
        expanded.append(baseContent);

        // 后一个 chunk
        String nextContent = getChunkContent(docId + "::" + (chunkIndex + 1));
        if (nextContent != null) {
            expanded.append("\n\n").append(nextContent);
        }

        // 上限 6000 字符，确保大 chunk 的关键内容不被截断
        String result = expanded.toString();
        return result.length() > 6000 ? result.substring(0, 6000) : result;
    }

    private String getDocMetadata(String docId, String key) {
        Object val = redisTemplate.opsForHash().get(DOCUMENT_PREFIX + docId, key);
        return val != null ? val.toString() : "";
    }

    private String resolveFileName(String chunkId, Map<String, Object> candidate) {
        String fileName = getDocMetadata(extractDocId(chunkId), "fileName");
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        Object payloadName = candidate != null ? candidate.get("fileName") : null;
        if (payloadName != null && !payloadName.toString().isBlank()) {
            return payloadName.toString();
        }
        Object libraryTitle = candidate != null ? candidate.get("libraryTitle") : null;
        return libraryTitle != null ? libraryTitle.toString() : "";
    }

    public List<String> getAllDocumentIds() {
        try {
            Set<String> keys = redisTemplate.keys(DOCUMENT_PREFIX + "*");
            if (keys == null) return Collections.emptyList();
            return keys.stream()
                .map(key -> key.substring(DOCUMENT_PREFIX.length()))
                .toList();
        } catch (Exception e) {
            log.warn("获取文档ID列表失败", e);
            return Collections.emptyList();
        }
    }

    public void updateDocument(String documentId, String content, Map<String, Object> metadata) {
        log.info("更新文档: documentId={}", documentId);
        removeDocument(documentId);
        addDocument(documentId, content, metadata);
    }

    public void indexDocument(String documentId, String content, String fileName, String fileType) {
        indexDocument(documentId, content, null, fileName, fileType);
    }

    public void indexDocument(String documentId, String content, byte[] rawData, String fileName, String fileType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", fileName);
        metadata.put("fileType", fileType);
        indexDocumentWithMetadata(documentId, content, rawData, metadata);
    }

    public void indexDocumentWithMetadata(String documentId, String content, byte[] rawData, Map<String, Object> extraMetadata) {
        log.info("索引文档: documentId={}, hasRawData={}", documentId, rawData != null);
        Map<String, Object> metadata = new HashMap<>();
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        metadata.putIfAbsent("uploadTime", System.currentTimeMillis());
        removeDocument(documentId);
        addDocument(documentId, content, rawData, metadata);
    }

    /**
     * 清空所有知识库数据（Redis 元数据 + Qdrant 向量 + ES 索引）
     */
    public void clearAll() {
        log.info("开始清空知识库所有数据");
        // 1. 删除 Redis 中所有 doc:* 元数据与切片缓存
        try {
            Set<String> keys = redisTemplate.keys(DOCUMENT_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("已删除 Redis 文档元数据: {} 条", keys.size());
            }
            Set<String> chunkKeys = redisTemplate.keys(CHUNK_PREFIX + "*");
            if (chunkKeys != null && !chunkKeys.isEmpty()) {
                redisTemplate.delete(chunkKeys);
                log.info("已删除 Redis 切片缓存: {} 条", chunkKeys.size());
            }
            Set<String> docChunkKeys = redisTemplate.keys(DOC_CHUNKS_PREFIX + "*");
            if (docChunkKeys != null && !docChunkKeys.isEmpty()) {
                redisTemplate.delete(docChunkKeys);
                log.info("已删除 Redis 切片索引: {} 条", docChunkKeys.size());
            }
        } catch (Exception e) {
            log.warn("清理 Redis 文档数据失败: {}", e.getMessage());
        }
        // 2. 删除 Qdrant 集合
        try {
            vectorStore.deleteCollection();
        } catch (Exception e) {
            log.warn("清理 Qdrant 数据失败: {}", e.getMessage());
        }
        // 3. 清空 ES rag_chunks 索引
        try {
            if (elasticsearchOperations != null) {
                var indexOps = elasticsearchOperations.indexOps(ChunkDocument.class);
                if (indexOps.exists()) {
                    indexOps.delete();
                    indexOps.createWithMapping();
                    log.info("已重建 ES 索引 rag_chunks");
                }
            }
        } catch (Exception e) {
            log.warn("清理 ES 数据失败: {}", e.getMessage());
        }
        log.info("知识库数据清空完成");
    }
}
