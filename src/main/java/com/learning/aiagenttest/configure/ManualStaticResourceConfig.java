package com.learning.aiagenttest.configure;

import com.learning.aiagenttest.constants.FileConstant;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class ManualStaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String manualRoot = Path.of(FileConstant.FILE_SAVE_DIR, "manuals").toUri().toString();
        registry.addResourceHandler("/manual-files/**")
                .addResourceLocations(manualRoot);
    }
}
