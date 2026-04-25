package com.learning.aiagenttest.service;

import com.learning.aiagenttest.model.manual.WordParseResult;

import java.io.IOException;
import java.nio.file.Path;

public interface WordMarkdownConverter {

    WordParseResult convert(Path sourceFile, String manualId, String originalFileName, String manualFileUrlPrefix) throws IOException;
}
