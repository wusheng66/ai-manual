package com.learning.aiagenttest.app;

import com.learning.aiagenttest.advisors.CustomerAdvisor;
import com.learning.aiagenttest.advisors.ReReadAdvisor;
import com.learning.aiagenttest.advisors.SensitiveWordAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;

@Configuration
@Component
@Slf4j
public class SystemManual {

    private final ChatClient chatClient;

    private final ChatMemory dbChatMemory;

    private final VectorStore pgVectorStore;

    private final String systemResource = "prompts/system-message.st";

    private final ToolCallback[] toolCallbacks;

    private static final String SYSTEM_PROMPT = """
            *** 角色定义 ***
            你是一位资深的 [PSM] 操作专家和技术文档专员。你对 [PSM] 的所有功能模块、操作流程及故障排查了如指掌。
            *** 任务目标 ***
            你的主要任务是协助用户解决在使用 [PSM] 过程中遇到的操作疑问，提供清晰的步骤指导，并帮助用户理解系统逻辑。
            *** 回答准则 ***
            分步指导：在解释操作步骤时，必须使用编号列表（1, 2, 3...）清晰展示，确保用户能按顺序执行。
            简洁明了：避免使用晦涩难懂的技术术语，除非必要，否则请用通俗易懂的语言解释。
            结构化输出：使用加粗强调关键按钮或菜单名称（例如：点击设置按钮）。
            主动引导：在回答完当前问题后，根据上下文，适当询问用户是否需要进行下一步操作或相关设置。
            故障排查：如果用户报告错误，先引导用户提供具体的错误代码或截图，再进行诊断。
            *** 开场白 ***
            “您好！我是 [系统名称] 的智能操作助手。无论是功能查询、流程指引还是故障排查，我都可以为您提供帮助。请问您现在遇到了什么问题？”
            *** 语言风格 ***
            专业、客观、耐心、乐于助人。
            """;

    //
//    public SystemManual(ChatModel dashscopeChatModel) {
//        // 初始化基于内存的对话记忆
//        ChatMemory chatMemory = new InMemoryChatMemory();
//        chatClient = ChatClient.builder(dashscopeChatModel)
//                .defaultSystem(SYSTEM_PROMPT)
//                .defaultAdvisors(
//                        new MessageChatMemoryAdvisor(chatMemory)
//                )
//                .build();
//    }


    public SystemManual(ChatModel ollamaChatModel, ChatMemory dbChatMemory, ToolCallback[] toolCallbacks, VectorStore pgVectorStore) {
        this.dbChatMemory = dbChatMemory;
        this.pgVectorStore = pgVectorStore;
        this.toolCallbacks = toolCallbacks;
        // 初始化基于内存的对话记忆
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(new ClassPathResource(systemResource));
        Prompt prompt = systemPromptTemplate.create();

        ChatClient.Builder builder = ChatClient.builder(ollamaChatModel)
                .defaultSystem(prompt.getContents());
        RetrievalAugmentationAdvisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
//                .queryTransformers(RewriteQueryTransformer.builder().chatClientBuilder(builder).build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.80)
                        .vectorStore(pgVectorStore)
                        .topK(5)
                        .build())
                .build();
        builder.defaultTools(toolCallbacks);

        builder.defaultAdvisors(
                new MessageChatMemoryAdvisor(dbChatMemory),
                new ReReadAdvisor(),
                new CustomerAdvisor(),
                new SensitiveWordAdvisor(),
                retrievalAugmentationAdvisor
        );
        chatClient = builder.build();
    }

    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .call().chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content : {}", content);
        return content;
    }

    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .stream()
                .content();
    }


    public record ExecuteSteps(String title, List<String> steps) {
    }

    public ExecuteSteps doChatWithReport(String message, String chatId) {
        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemResource);
        Prompt prompt = systemPromptTemplate.create();
        ExecuteSteps executeSteps = chatClient
                .prompt()
                .system(prompt.getContents() + "每次对话后都要生成操作步骤报告，报告标题为{问题}，内容为操作步骤。 格式： {title: \"标题（问题简写）\", steps: [\"步骤N\"]}")
                .user(message)
                .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                        .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
                .call()
                .entity(ExecuteSteps.class);
        log.info("executeSteps: {}", executeSteps);
        return executeSteps;
    }


}
