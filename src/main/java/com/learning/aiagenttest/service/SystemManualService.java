package com.learning.aiagenttest.service;

import org.springframework.web.multipart.MultipartFile;

public interface SystemManualService {
    void parseManual(MultipartFile file);
}
