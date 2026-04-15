package com.learning.aiagenttest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.learning.aiagenttest.domain.ChatHistory;
import com.learning.aiagenttest.service.ChatHistoryService;
import com.learning.aiagenttest.mapper.ChatHistoryMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
* @author huangyingwu
* @description 针对表【chat_history】的数据库操作Service实现
* @createDate 2026-04-13 23:13:39
*/
@Service
public class ChatHistoryServiceImpl extends ServiceImpl<ChatHistoryMapper, ChatHistory>
    implements ChatHistoryService{

    @Override
    public List<Message> getByConversionId(String conversationId, int lastN) {
        LambdaQueryWrapper<ChatHistory> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatHistory::getConversationId, conversationId);
        queryWrapper.orderByDesc(ChatHistory::getTimestamp);
        queryWrapper.last(String.format("limit %s", lastN));
        List<ChatHistory> list = this.list(queryWrapper);
        List<Message> messages = new ArrayList<>();
        if (!list.isEmpty()) {
            messages.addAll(list.stream().map(m -> new UserMessage(m.getContent())).toList());
        }
        return messages;
    }
}




