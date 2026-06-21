package com.javaee.docmanager.ai.rag;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * Elasticsearch 文档切片索引实体（BM25 关键词检索）。
 */
@Document(indexName = "rag_chunks")
public class ChunkDocument {

    @Id
    @Field(type = FieldType.Keyword)
    private String chunkId;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String content;

    @Field(type = FieldType.Keyword)
    private String docId;

    @Field(type = FieldType.Keyword)
    private String fileName;

    public ChunkDocument() {
    }

    public ChunkDocument(String chunkId, String content, String docId, String fileName) {
        this.chunkId = chunkId;
        this.content = content;
        this.docId = docId;
        this.fileName = fileName;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}
