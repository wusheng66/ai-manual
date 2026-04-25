package com.learning.aiagenttest.service;

import com.learning.aiagenttest.model.manual.ManualUploadResult;
import org.springframework.web.multipart.MultipartFile;

public interface SystemManualService {
    ManualUploadResult parseManual(MultipartFile file);
}
