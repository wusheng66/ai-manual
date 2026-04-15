package com.learning.aiagenttest.memory;

import cn.hutool.core.util.StrUtil;
import com.learning.aiagenttest.domain.ChatHistory;
import com.learning.aiagenttest.service.ChatHistoryService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Component
public class DbChatMemory implements ChatMemory {

    @Autowired
    private ChatHistoryService chatHistoryService;


    @Override
    public void add(String conversationId, List<Message> messages) {
        ArrayList<ChatHistory> chatHistories = new ArrayList<>();
        if (StrUtil.equals(conversationId, "default")) {
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            ChatHistory history = new ChatHistory();
            history.setConversationId(conversationId);
            history.setContent(message.getText());
            history.setMessageType(message.getMessageType().getValue());
            history.setTimestamp(new Date());
            chatHistories.add(history);
        }
        chatHistoryService.saveBatch(chatHistories);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        return chatHistoryService.getByConversionId(conversationId, lastN);
    }

    @Override
    public void clear(String conversationId) {

    }
}
