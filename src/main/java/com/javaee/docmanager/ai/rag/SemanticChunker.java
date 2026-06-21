package com.javaee.docmanager.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 语义细分块器（RAG 分块第二阶段）
 * 将结构化粗切分结果进一步切分为适合向量化的语义块，保留重叠上下文。
 */
@Component
public class SemanticChunker {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);

    private static final int TARGET_SIZE = 800;
    private static final int MAX_SIZE = 1200;
    private static final int OVERLAP = 100;
    private static final int MIN_SIZE = 80;

    /**
     * 对粗切分块列表做语义细分。
     */
    public List<String> chunk(List<String> structuralChunks) {
        if (structuralChunks == null || structuralChunks.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        for (String block : structuralChunks) {
            if (block == null || block.isBlank()) {
                continue;
            }
            String trimmed = block.strip();
            if (trimmed.length() <= MAX_SIZE) {
                if (trimmed.length() >= MIN_SIZE || result.isEmpty()) {
                    result.add(trimmed);
                } else {
                    mergeIntoLast(result, trimmed);
                }
            } else {
                result.addAll(splitWithOverlap(trimmed));
            }
        }

        log.debug("语义切分: 输入块数={}, 输出块数={}", structuralChunks.size(), result.size());
        return result;
    }

    private void mergeIntoLast(List<String> result, String small) {
        if (result.isEmpty()) {
            result.add(small);
            return;
        }
        int last = result.size() - 1;
        String merged = result.get(last) + "\n\n" + small;
        if (merged.length() <= MAX_SIZE) {
            result.set(last, merged);
        } else {
            result.add(small);
        }
    }

    private List<String> splitWithOverlap(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int len = text.length();

        while (start < len) {
            int end = Math.min(start + TARGET_SIZE, len);

            if (end < len) {
                int boundary = findBoundary(text, start, end);
                if (boundary > start + MIN_SIZE) {
                    end = boundary;
                }
            }

            String piece = text.substring(start, end).strip();
            if (!piece.isBlank()) {
                chunks.add(piece);
            }

            if (end >= len) {
                break;
            }
            start = Math.max(end - OVERLAP, start + MIN_SIZE);
        }

        return chunks;
    }

    /**
     * 在目标位置附近寻找自然断点（段落、句号、换行）。
     */
    private int findBoundary(String text, int start, int preferredEnd) {
        int searchStart = Math.max(start + MIN_SIZE, preferredEnd - 200);
        int searchEnd = Math.min(preferredEnd + 50, text.length());

        String window = text.substring(searchStart, searchEnd);
        int[] priorities = {
                window.lastIndexOf("\n\n"),
                window.lastIndexOf('\n'),
                window.lastIndexOf("。"),
                window.lastIndexOf("！"),
                window.lastIndexOf("？"),
                window.lastIndexOf(". "),
                window.lastIndexOf(' ')
        };

        for (int idx : priorities) {
            if (idx > 0) {
                int absolute = searchStart + idx;
                if (absolute > start + MIN_SIZE) {
                    return absolute + (idx == window.lastIndexOf(". ") ? 1 : 1);
                }
            }
        }
        return preferredEnd;
    }
}
