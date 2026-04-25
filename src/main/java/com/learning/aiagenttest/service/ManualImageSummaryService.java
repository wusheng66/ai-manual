package com.learning.aiagenttest.service;

import java.nio.file.Path;

public interface ManualImageSummaryService {

    String summarize(Path imagePath, String title, String sectionPath, String ocrText);
}
