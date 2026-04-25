package com.learning.aiagenttest.service.impl;

import com.learning.aiagenttest.service.ManualImageOcrService;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
public class ManualImageOcrServiceImpl implements ManualImageOcrService {

    @Value("${manual.ocr.enabled:true}")
    private boolean enabled;

    @Value("${manual.ocr.tessdata-path:}")
    private String tessdataPath;

    @Value("${manual.ocr.language:chi_sim+eng}")
    private String language;

    @Override
    public String recognizeText(Path imagePath) {
        if (!enabled || imagePath == null || !Files.exists(imagePath)) {
            return "";
        }
        if (tessdataPath == null || tessdataPath.isBlank()) {
            log.warn("OCR 未配置 tessdata 路径，跳过图片识别: {}", imagePath);
            return "";
        }
        Path tessdataDir = Path.of(tessdataPath);
        if (!Files.isDirectory(tessdataDir)) {
            log.warn("OCR tessdata 路径不存在，跳过图片识别: {}", tessdataDir);
            return "";
        }

        try {
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessdataDir.toAbsolutePath().toString());
            tesseract.setLanguage(language);
            String text = tesseract.doOCR(imagePath.toFile());
            return text == null ? "" : text.trim();
        } catch (TesseractException e) {
            log.warn("图片 OCR 识别失败: {}", imagePath, e);
            return "";
        }
    }
}
