package com.learning.aiagenttest.configure;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class PgVectorStoreConfig {

    @Bean
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
// 1. 构建 Embedding 模型 (负责计算向量)
        OllamaEmbeddingModel ollamaEmbeddingModel = OllamaEmbeddingModel.builder()
                .ollamaApi(new OllamaApi())
                .defaultOptions(OllamaOptions.builder()
                        .model("mxbai-embed-large")
                        .numCtx(2048)
                        .build())
                .build();

        // 2. 构建 PG Vector Store (负责存储到数据库)
        return PgVectorStore.builder(jdbcTemplate, ollamaEmbeddingModel) // 传入数据库连接
                .initializeSchema(true) // 【关键】自动建表！如果表不存在会自动创建 vector_store
                .schemaName("manual_db")
                .vectorTableName("vector_store") // 指定表名
                .indexType(PgVectorStore.PgIndexType.HNSW) // 指定索引类型 (可选，HNSW 查询更快)
                .build();
    }
}
