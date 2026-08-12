package com.diet.model;

import java.util.Map;

import com.diet.enums.SourceMode;
import com.fasterxml.jackson.annotation.JsonAutoDetect;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {
    private String sessionId;// 会话 ID，用于关联用户会话
    private String message;// 用户消息
    private SourceMode sourceMode;// 数据来源模式，个人/公共
    private Map<String, Object> context;// 上下文信息，如用户 ID、会话 ID 等
}