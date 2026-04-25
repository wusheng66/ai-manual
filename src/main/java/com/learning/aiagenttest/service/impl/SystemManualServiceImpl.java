package com.learning.aiagenttest.service.impl;

import com.learning.aiagenttest.constants.FileConstant;
import com.learning.aiagenttest.model.manual.ManualBlock;
import com.learning.aiagenttest.model.manual.ManualUploadResult;
import com.learning.aiagenttest.model.manual.WordParseResult;
import com.learning.aiagenttest.service.MultiAgentCollaborationService;
import com.learning.aiagenttest.service.SystemManualService;
import com.learning.aiagenttest.service.WordMarkdownConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.KeywordMetadataEnricher;
import org.springframework.ai.transformer.SummaryMetadataEnricher;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class SystemManualServiceImpl implements SystemManualService {

    private static final String MANUAL_FILE_URL_PREFIX = "/api/manual-files/";
    private static final String MANUAL_PREVIEW_URL_PREFIX = "/api/sys/manual/preview?markdownPath=";

    @Autowired
    private VectorStore pgVectorStore;

    @Autowired
    private ChatModel ollamaChatModel;

    @Autowired
    private WordMarkdownConverter wordMarkdownConverter;

    @Autowired
    private MultiAgentCollaborationService multiAgentCollaborationService;

    @Override
    public ManualUploadResult parseManual(MultipartFile file) {
        validateFile(file);
        String manualId = UUID.randomUUID().toString();
        String originalFileName = file.getOriginalFilename() == null ? "manual.docx" : file.getOriginalFilename();
        Path manualRoot = Path.of(FileConstant.FILE_SAVE_DIR, "manuals", manualId);
        Path sourceDir = manualRoot.resolve("source");
        Path markdownDir = manualRoot.resolve("markdown");
        try {
            Files.createDirectories(sourceDir);
            Files.createDirectories(markdownDir);
            Path sourcePath = sourceDir.resolve(originalFileName);
            saveMultipartFile(file, sourcePath);

            String manualFileUrlPrefix = MANUAL_FILE_URL_PREFIX + manualId + "/";
            WordParseResult parseResult = wordMarkdownConverter.convert(sourcePath, manualId, originalFileName, manualFileUrlPrefix);
            Path markdownPath = markdownDir.resolve("manual.md");
            Files.writeString(markdownPath, parseResult.getMarkdown());
            parseResult.setMarkdownPath(markdownPath);

            ingestBlocks(parseResult, originalFileName);
            refreshKnowledgeVersion(manualId);
            String markdownUrl = buildManualFileUrl(manualId, markdownPath);
            log.info("手册解析完成，manualId={}, blocks={}, images={}, markdownPath={}",
                    manualId, parseResult.getBlocks().size(), parseResult.getImageCount(), markdownPath);
            return ManualUploadResult.builder()
                    .manualId(manualId)
                    .imageCount(parseResult.getImageCount())
                    .markdownPath(markdownUrl)
                    .previewUrl(buildPreviewUrl(markdownUrl))
                    .blockCount(parseResult.getBlocks().size())
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("处理 Word 手册失败", e);
        }
    }

    private void refreshKnowledgeVersion(String manualId) {
        String newVersion = "manual-" + manualId;
        try {
            String result = multiAgentCollaborationService.switchKnowledgeVersion(newVersion, true, "upload-" + manualId);
            log.info("知识库版本已刷新，manualId={}, result={}", manualId, result);
        } catch (Exception ex) {
            // 手册上传成功不应因缓存版本切换失败而回滚
            log.warn("知识库版本刷新失败，manualId={}, error={}", manualId, ex.getMessage());
        }
    }

    private void ingestBlocks(WordParseResult parseResult, String originalFileName) {
        List<Document> documents = new ArrayList<>();
        for (ManualBlock block : parseResult.getBlocks()) {
            Map<String, Object> metadata = new HashMap<>(block.getMetadata());
            metadata.put("manualId", parseResult.getManualId());
            metadata.put("fileName", originalFileName);
            metadata.put("markdownPath", parseResult.getMarkdownPath() == null ? "" : parseResult.getMarkdownPath().toString());
            documents.add(new Document(block.getPlainText(), metadata));
        }
        if (documents.isEmpty()) {
            log.warn("手册未提取到可向量化内容，manualId={}", parseResult.getManualId());
            return;
        }

        TokenTextSplitter textSplitter = new TokenTextSplitter(300, 80, 16, 10000, true);
        List<Document> splitDocuments = textSplitter.apply(documents);
        log.info("文本切割完成, manualId={}, chunks={}", parseResult.getManualId(), splitDocuments.size());

        SummaryMetadataEnricher enricher = new SummaryMetadataEnricher(ollamaChatModel,
                List.of(SummaryMetadataEnricher.SummaryType.CURRENT));
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(ollamaChatModel, 3);
        List<Document> enrichDoc = keywordMetadataEnricher.apply(enricher.apply(splitDocuments));
        pgVectorStore.write(enrichDoc);
        log.info("文件向量化存储完成, manualId={}, vectors={}", parseResult.getManualId(), enrichDoc.size());
    }

    private String buildManualFileUrl(String manualId, Path targetPath) {
        Path manualBasePath = Path.of(FileConstant.FILE_SAVE_DIR, "manuals", manualId);
        Path relativePath = manualBasePath.relativize(targetPath);
        return MANUAL_FILE_URL_PREFIX + manualId + "/" + relativePath.toString().replace('\\', '/');
    }

    private String buildPreviewUrl(String markdownUrl) {
        return MANUAL_PREVIEW_URL_PREFIX + UriUtils.encode(markdownUrl, StandardCharsets.UTF_8);
    }

    private void saveMultipartFile(MultipartFile file, Path targetPath) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".docx")) {
            throw new IllegalArgumentException("当前仅支持上传 docx 格式手册");
        }
    }
}
