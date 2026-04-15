package com.learning.aiagenttest.service;

import com.learning.aiagenttest.domain.ChatHistory;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
* @author huangyingwu
* @description 针对表【chat_history】的数据库操作Service
* @createDate 2026-04-13 23:13:39
*/
public interface ChatHistoryService extends IService<ChatHistory> {

    List<Message> getByConversionId(String conversationId, int lastN);
}
