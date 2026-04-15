package com.learning.aiagenttest.service.impl;

import com.learning.aiagenttest.service.SystemManualService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.KeywordMetadataEnricher;
import org.springframework.ai.transformer.SummaryMetadataEnricher;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@Slf4j
public class SystemManualServiceImpl implements SystemManualService {
    @Autowired
    private VectorStore pgVectorStore;
    @Autowired
    private ChatModel ollamaChatModel;


    /**
     * 读取 Word 文档并转换为向量存入数据库
     */
    public void ingestWordDocument(Resource resource) {
        log.info("执行文件读取");
        // 1. 读取文档
        // TikaDocumentReader 可以自动识别 .docx, .pdf, .txt 等格式
        TikaDocumentReader documentReader = new TikaDocumentReader(resource);

        // 获取原始文档列表
        List<Document> documents = documentReader.get();
        log.info("初始化文档获取成功");

        // 2. 文本切分 (Splitting)
        // 将长文档切分成小的片段 (Chunk)，避免超过 LLM 的上下文限制
        // 默认配置通常足够，也可以自定义 chunkSize
        TokenTextSplitter textSplitter = new TokenTextSplitter(255, 100, 16, 10000, true);
        List<Document> splitDocuments = textSplitter.apply(documents);
        log.info("文本切割完成");
        SummaryMetadataEnricher enricher = new SummaryMetadataEnricher(ollamaChatModel,
                List.of(SummaryMetadataEnricher.SummaryType.CURRENT));
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(ollamaChatModel, 3);

        List<Document> enrichDoc = keywordMetadataEnricher.apply(enricher.apply(splitDocuments));
        log.info("文档信息提取完成");
        pgVectorStore.write(enrichDoc);
        log.info("文件向量化存储完成");

    }

    @Override
    public void parseManual(MultipartFile file) {
        ingestWordDocument(file.getResource());
    }
}
