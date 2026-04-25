package com.learning.aiagenttest.model.manual;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WordParseResult {

    private String manualId;

    private String originalFileName;

    private String markdown;

    private Path markdownPath;

    @Builder.Default
    private List<ManualBlock> blocks = new ArrayList<>();

    private int imageCount;
}
