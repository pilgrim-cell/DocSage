package com.javaee.docmanager.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查询改写器：提取技术实体词，生成向量检索与关键词检索两套查询。
 */
@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private static final Pattern TECH_TERM = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_\\-]{1,}(?:\\.[A-Za-z][A-Za-z0-9_]*)*");
    private static final Pattern VERSION = Pattern.compile(
            "\\b(?:v|V)?\\d+(?:\\.\\d+){1,3}\\b");
    private static final Pattern CAMEL_CASE = Pattern.compile(
            "[a-z]+(?:[A-Z][a-z0-9]+)+");

    /**
     * 解析查询，输出向量检索与关键词检索用的不同表达。
     */
    public QueryParts extract(String query) {
        if (query == null || query.isBlank()) {
            return new QueryParts("", "", List.of());
        }

        String normalized = query.strip();
        Set<String> techTerms = new LinkedHashSet<>();

        collectMatches(TECH_TERM, normalized, techTerms);
        collectMatches(VERSION, normalized, techTerms);
        collectMatches(CAMEL_CASE, normalized, techTerms);

        // 英文全大写缩写（如 RAG、JWT、API）
        Matcher upper = Pattern.compile("\\b[A-Z]{2,}\\b").matcher(normalized);
        while (upper.find()) {
            techTerms.add(upper.group());
        }

        List<String> termList = new ArrayList<>(techTerms);
        String keywordQuery = buildKeywordQuery(normalized, termList);
        String rewrittenForVector = buildVectorQuery(normalized, termList);

        log.debug("查询改写: original='{}', vector='{}', keyword='{}', techTerms={}",
                normalized, rewrittenForVector, keywordQuery, termList);

        return new QueryParts(normalized, rewrittenForVector, keywordQuery, termList);
    }

    private void collectMatches(Pattern pattern, String text, Set<String> out) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String term = m.group().strip();
            if (term.length() >= 2) {
                out.add(term);
            }
        }
    }

    private String buildKeywordQuery(String original, List<String> techTerms) {
        if (!techTerms.isEmpty()) {
            return String.join(" ", techTerms);
        }
        return original;
    }

    private String buildVectorQuery(String original, List<String> techTerms) {
        if (techTerms.isEmpty()) {
            return original;
        }
        StringBuilder sb = new StringBuilder(original);
        sb.append(" 相关技术术语：");
        sb.append(String.join("、", techTerms));
        return sb.toString();
    }

    public static class QueryParts {
        public final String original;
        public final String rewrittenForVector;
        private final String keywordQuery;
        private final List<String> techTerms;

        public QueryParts(String original, String rewrittenForVector, List<String> techTerms) {
            this(original, rewrittenForVector, buildKeywordFromTerms(original, techTerms), techTerms);
        }

        public QueryParts(String original, String rewrittenForVector, String keywordQuery, List<String> techTerms) {
            this.original = original;
            this.rewrittenForVector = rewrittenForVector;
            this.keywordQuery = keywordQuery;
            this.techTerms = techTerms != null ? techTerms : List.of();
        }

        private static String buildKeywordFromTerms(String original, List<String> techTerms) {
            if (techTerms != null && !techTerms.isEmpty()) {
                return String.join(" ", techTerms);
            }
            return original;
        }

        public boolean hasTechTerms() {
            return !techTerms.isEmpty();
        }

        public String getKeywordQuery() {
            return keywordQuery != null ? keywordQuery : original;
        }

        public List<String> getTechTerms() {
            return techTerms;
        }
    }
}
