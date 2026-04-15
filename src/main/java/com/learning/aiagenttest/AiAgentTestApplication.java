package com.learning.aiagenttest;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.learning.aiagenttest.mapper")
public class AiAgentTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAgentTestApplication.class, args);
    }

}
