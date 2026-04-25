package com.learning.aiagenttest.configure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**") // 匹配你的接口路径
                        .allowedOriginPatterns("*") // 允许所有域名（比 allowedOrigins 更灵活）
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("X-Trace-Id")
                        .allowCredentials(true) // 如果前端带了 cookie，这里必须为 true
                        .maxAge(3600); // 预检请求的缓存时间
            }
        };
    }
}