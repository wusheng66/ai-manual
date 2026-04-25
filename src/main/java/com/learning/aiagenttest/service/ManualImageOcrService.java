package com.learning.aiagenttest.service;

import java.nio.file.Path;

public interface ManualImageOcrService {

    String recognizeText(Path imagePath);
}
