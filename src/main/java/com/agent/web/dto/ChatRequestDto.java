package com.agent.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 发消息的请求体。
 *
 * <pre>
 * POST /api/chat
 * { "conversationId": 1, "message": "当前目录有哪些文件？" }
 * </pre>
 *
 * <p>会话持久化之后 {@code conversationId} 变成**必填** —— 之前是服务端按 sessionId
 * 自动建，现在会话是一等实体，由客户端先创建再引用，语义更清晰，
 * 也让"这个 id 是不是你的"成为每次请求都要校验的事。
 */
public record ChatRequestDto(
        @NotNull(message = "conversationId 不能为空")
        Long conversationId,

        @NotBlank(message = "message 不能为空")
        String message
) {
}
