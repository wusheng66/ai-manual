package com.learning.aiagenttest.model.manual;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualUploadResult {

    private String manualId;

    private int imageCount;

    private String markdownPath;

    private String previewUrl;

    private int blockCount;
}
