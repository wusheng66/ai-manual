package com.learning.aiagenttest.model.manual;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManualBlock {

    private String blockId;

    private Integer orderNo;

    private ManualBlockType blockType;

    private String title;

    private String sectionPath;

    private String markdownContent;

    private String plainText;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
