package com.learning.aiagenttest.service.impl;

import com.learning.aiagenttest.model.manual.ManualBlock;
import com.learning.aiagenttest.model.manual.ManualBlockType;
import com.learning.aiagenttest.model.manual.WordParseResult;
import com.learning.aiagenttest.service.ManualImageOcrService;
import com.learning.aiagenttest.service.ManualImageSummaryService;
import com.learning.aiagenttest.service.WordMarkdownConverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WordMarkdownConverterImpl implements WordMarkdownConverter {

    @Autowired
    private ManualImageOcrService manualImageOcrService;

    @Autowired
    private ManualImageSummaryService manualImageSummaryService;

    @Override
    public WordParseResult convert(Path sourceFile, String manualId, String originalFileName, String manualFileUrlPrefix) throws IOException {
        Path sourceDir = sourceFile.getParent();
        Path manualDir = sourceDir != null && sourceDir.getParent() != null ? sourceDir.getParent() : sourceFile.getParent();
        Path imageDir = manualDir.resolve("images");
        Files.createDirectories(imageDir);

        StringBuilder markdown = new StringBuilder();
        List<ManualBlock> blocks = new ArrayList<>();
        Map<Integer, String> headingStack = new LinkedHashMap<>();
        int orderNo = 1;
        int imageCount = 0;

        try (InputStream inputStream = Files.newInputStream(sourceFile);
            XWPFDocument document = new XWPFDocument(inputStream)) {
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    String text = cleanText(paragraph.getText());
                    Integer headingLevel = resolveHeadingLevel(paragraph);
                    if (headingLevel != null && !text.isBlank()) {
                        updateHeadingStack(headingStack, headingLevel, text);
                        String headingMarkdown = "#".repeat(headingLevel) + " " + text;
                        markdown.append(headingMarkdown).append("\n\n");
                        blocks.add(buildBlock(manualId, orderNo++, ManualBlockType.TEXT, text,
                                buildSectionPath(headingStack), headingMarkdown, text, new HashMap<>()));
                    } else if (!text.isBlank()) {
                        String sectionPath = buildSectionPath(headingStack);
                        markdown.append(text).append("\n\n");
                        blocks.add(buildBlock(manualId, orderNo++, ManualBlockType.TEXT, lastHeading(headingStack),
                                sectionPath, text, buildTextPlainText(sectionPath, lastHeading(headingStack), text), new HashMap<>()));
                    }

                    for (XWPFRun run : paragraph.getRuns()) {
                        for (XWPFPicture picture : run.getEmbeddedPictures()) {
                            XWPFPictureData pictureData = picture.getPictureData();
                            if (pictureData == null) {
                                continue;
                            }
                            imageCount++;
                            String extension = pictureData.suggestFileExtension();
                            if (extension == null || extension.isBlank()) {
                                extension = "png";
                            }
                            String imageFileName = String.format("image-%03d.%s", imageCount, extension);
                            Path imagePath = imageDir.resolve(imageFileName);
                            Files.write(imagePath, pictureData.getData(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                            String imageTitle = !text.isBlank() ? text : "图片-" + imageCount;
                            String imageUrl = manualFileUrlPrefix + "images/" + imageFileName;
                            String imageMarkdown = "![" + imageTitle + "](" + imageUrl + ")";
                            markdown.append(imageMarkdown).append("\n\n");

                            Map<String, Object> imageMetadata = new HashMap<>();
                            imageMetadata.put("imagePath", imagePath.toString());
                            imageMetadata.put("imageFileName", imageFileName);
                            imageMetadata.put("imageUrl", imageUrl);

                            String sectionPath = buildSectionPath(headingStack);
                            String ocrText = cleanText(manualImageOcrService.recognizeText(imagePath));
                            String imageSummary = cleanText(manualImageSummaryService.summarize(imagePath, imageTitle, sectionPath, ocrText));
                            imageMetadata.put("ocrText", ocrText);
                            imageMetadata.put("imageSummary", imageSummary);
                            String imagePlainText = buildImagePlainText(sectionPath, imageTitle, imagePath, imageUrl, ocrText, imageSummary);
                            blocks.add(buildBlock(manualId, orderNo++, ManualBlockType.IMAGE, imageTitle,
                                    sectionPath, imageMarkdown, imagePlainText, imageMetadata));
                        }
                    }
                }

                if (bodyElement instanceof XWPFTable table) {
                    String tableMarkdown = toMarkdownTable(table);
                    if (tableMarkdown.isBlank()) {
                        continue;
                    }
                    String sectionPath = buildSectionPath(headingStack);
                    String title = lastHeading(headingStack);
                    markdown.append(tableMarkdown).append("\n\n");
                    blocks.add(buildBlock(manualId, orderNo++, ManualBlockType.TABLE, title,
                            sectionPath, tableMarkdown, buildTablePlainText(sectionPath, title, table), new HashMap<>()));
                }
            }
        }

        return WordParseResult.builder()
                .manualId(manualId)
                .originalFileName(originalFileName)
                .markdown(markdown.toString().trim())
                .blocks(blocks)
                .imageCount(imageCount)
                .build();
    }

    private ManualBlock buildBlock(String manualId, int orderNo, ManualBlockType type, String title,
                                   String sectionPath, String markdownContent, String plainText,
                                   Map<String, Object> metadata) {
        metadata.put("manualId", manualId);
        metadata.put("orderNo", orderNo);
        metadata.put("blockType", type.name());
        metadata.put("title", title == null ? "" : title);
        metadata.put("sectionPath", sectionPath == null ? "" : sectionPath);
        return ManualBlock.builder()
                .blockId(UUID.randomUUID().toString())
                .orderNo(orderNo)
                .blockType(type)
                .title(title)
                .sectionPath(sectionPath)
                .markdownContent(markdownContent)
                .plainText(plainText)
                .metadata(metadata)
                .build();
    }

    private void updateHeadingStack(Map<Integer, String> headingStack, int headingLevel, String headingText) {
        headingStack.entrySet().removeIf(entry -> entry.getKey() >= headingLevel);
        headingStack.put(headingLevel, headingText);
    }

    private String buildSectionPath(Map<Integer, String> headingStack) {
        return headingStack.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" > "));
    }

    private String lastHeading(Map<Integer, String> headingStack) {
        String last = null;
        for (String value : headingStack.values()) {
            last = value;
        }
        return last;
    }

    private Integer resolveHeadingLevel(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) {
            return null;
        }
        String normalized = style.toLowerCase();
        if (normalized.startsWith("heading")) {
            String level = normalized.replace("heading", "").trim();
            return parseHeadingLevel(level);
        }
        if (normalized.startsWith("title")) {
            return 1;
        }
        return null;
    }

    private Integer parseHeadingLevel(String rawLevel) {
        if (rawLevel == null || rawLevel.isBlank()) {
            return null;
        }
        try {
            int level = Integer.parseInt(rawLevel);
            return Math.clamp(level, 1, 6);
        } catch (NumberFormatException e) {
            log.error("无法识别标题级别: {}", rawLevel, e);
            return null;
        }
    }

    private String toMarkdownTable(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(escapeMarkdownTableCell(cleanText(cell.getText())));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return "";
        }

        int maxColumns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (maxColumns == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        List<String> header = normalizeRow(rows.getFirst(), maxColumns);
        appendMarkdownRow(builder, header);
        appendMarkdownRow(builder, repeat("---", maxColumns));
        for (int i = 1; i < rows.size(); i++) {
            appendMarkdownRow(builder, normalizeRow(rows.get(i), maxColumns));
        }
        if (rows.size() == 1) {
            appendMarkdownRow(builder, repeat("", maxColumns));
        }
        return builder.toString().trim();
    }

    private List<String> normalizeRow(List<String> row, int maxColumns) {
        List<String> normalized = new ArrayList<>(row);
        while (normalized.size() < maxColumns) {
            normalized.add("");
        }
        return normalized;
    }

    private List<String> repeat(String value, int count) {
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(value);
        }
        return values;
    }

    private void appendMarkdownRow(StringBuilder builder, List<String> cells) {
        builder.append("| ");
        builder.append(String.join(" | ", cells));
        builder.append(" |\n");
    }

    private String buildTextPlainText(String sectionPath, String title, String text) {
        return "章节：" + defaultValue(sectionPath) + "\n标题：" + defaultValue(title) + "\n内容：\n" + text;
    }

    private String buildTablePlainText(String sectionPath, String title, XWPFTable table) {
        return "章节：" + defaultValue(sectionPath) + "\n标题：" + defaultValue(title) + "\n表格内容：\n" + cleanText(table.getText());
    }

    private String buildImagePlainText(String sectionPath, String title, Path imagePath, String imageUrl, String ocrText, String imageSummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("章节：").append(defaultValue(sectionPath))
                .append("\n标题：").append(defaultValue(title))
                .append("\n图片说明：文档中提取的配图。")
                .append("\n图片路径：").append(imagePath)
                .append("\n图片访问地址：").append(imageUrl);
        if (imageSummary != null && !imageSummary.isBlank()) {
            builder.append("\nAI摘要：\n").append(imageSummary);
        }
        if (ocrText != null && !ocrText.isBlank()) {
            builder.append("\nOCR文本：\n").append(ocrText);
        }
        return builder.toString();
    }

    private String defaultValue(String value) {
        return value == null || value.isBlank() ? "未分类" : value;
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\u00A0", " ")
                .replaceAll("[\t\r\f]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private String escapeMarkdownTableCell(String value) {
        return value.replace("|", "\\|").replaceAll("\n+", "<br/>");
    }
}
