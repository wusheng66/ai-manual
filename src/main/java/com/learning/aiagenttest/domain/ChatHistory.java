package com.learning.aiagenttest.domain;


import java.io.Serializable;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

/**
* 
* @TableName chat_history
*/
@Data
public class ChatHistory implements Serializable {

    /**
    * 
    */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
    * 
    */
    private String conversationId;
    /**
    * 
    */
    private String messageType;
    /**
    * 
    */
    private String content;
    /**
    * 
    */
    private Date timestamp;


}
